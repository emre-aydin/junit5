/*
 * Copyright 2015-2018 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution and is available at
 *
 * http://www.eclipse.org/legal/epl-v20.html
 */

package org.junit.jupiter.engine.descriptor;

import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toSet;
import static org.apiguardian.api.API.Status.INTERNAL;
import static org.junit.jupiter.engine.descriptor.DisplayNameUtils.determineDisplayName;
import static org.junit.platform.commons.util.AnnotationUtils.findAnnotation;
import static org.junit.platform.commons.util.AnnotationUtils.findRepeatableAnnotations;

import java.lang.reflect.AnnotatedElement;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.apiguardian.api.API;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.engine.config.JupiterConfiguration;
import org.junit.jupiter.engine.execution.ConditionEvaluator;
import org.junit.jupiter.engine.execution.JupiterEngineExecutionContext;
import org.junit.platform.commons.JUnitException;
import org.junit.platform.commons.logging.Logger;
import org.junit.platform.commons.logging.LoggerFactory;
import org.junit.platform.commons.util.ExceptionUtils;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.hierarchical.ExclusiveResource;
import org.junit.platform.engine.support.hierarchical.ExclusiveResource.LockMode;
import org.junit.platform.engine.support.hierarchical.Node;

/**
 * @since 5.0
 */
@API(status = INTERNAL, since = "5.0")
public abstract class JupiterTestDescriptor extends AbstractTestDescriptor
		implements Node<JupiterEngineExecutionContext> {

	private static final Logger logger = LoggerFactory.getLogger(JupiterTestDescriptor.class);

	private static final ConditionEvaluator conditionEvaluator = new ConditionEvaluator();

	protected final JupiterConfiguration configuration;

	JupiterTestDescriptor(UniqueId uniqueId, AnnotatedElement element, Supplier<String> displayNameSupplier,
			TestSource source, JupiterConfiguration configuration) {
		this(uniqueId, determineDisplayName(element, displayNameSupplier), source, configuration);
	}

	JupiterTestDescriptor(UniqueId uniqueId, String displayName, TestSource source,
			JupiterConfiguration configuration) {
		super(uniqueId, displayName, source);
		this.configuration = configuration;
	}

	// --- TestDescriptor ------------------------------------------------------

	protected static Set<TestTag> getTags(AnnotatedElement element) {
		// @formatter:off
		return findRepeatableAnnotations(element, Tag.class).stream()
				.map(Tag::value)
				.filter(tag -> {
					boolean isValid = TestTag.isValid(tag);
					if (!isValid) {
						// TODO [#242] Replace logging with precondition check once we have a proper mechanism for
						// handling validation exceptions during the TestEngine discovery phase.
						//
						// As an alternative to a precondition check here, we could catch any
						// PreconditionViolationException thrown by TestTag::create.
						logger.warn(() -> String.format(
							"Configuration error: invalid tag syntax in @Tag(\"%s\") declaration on [%s]. Tag will be ignored.",
							tag, element));
					}
					return isValid;
				})
				.map(TestTag::create)
				.collect(collectingAndThen(toCollection(LinkedHashSet::new), Collections::unmodifiableSet));
		// @formatter:on
	}

	// --- Node ----------------------------------------------------------------

	@Override
	public final ExecutionMode getExecutionMode() {
		Optional<ExecutionMode> executionMode = getExplicitExecutionMode();
		if (executionMode.isPresent()) {
			return executionMode.get();
		}
		Optional<TestDescriptor> parent = getParent();
		while (parent.isPresent() && parent.get() instanceof JupiterTestDescriptor) {
			JupiterTestDescriptor jupiterParent = (JupiterTestDescriptor) parent.get();
			executionMode = jupiterParent.getExplicitExecutionMode();
			if (executionMode.isPresent()) {
				return executionMode.get();
			}
			executionMode = jupiterParent.getDefaultChildExecutionMode();
			if (executionMode.isPresent()) {
				return executionMode.get();
			}
			parent = jupiterParent.getParent();
		}
		return toExecutionMode(configuration.getDefaultExecutionMode());
	}

	protected Optional<ExecutionMode> getExplicitExecutionMode() {
		return Optional.empty();
	}

	protected Optional<ExecutionMode> getDefaultChildExecutionMode() {
		return Optional.empty();
	}

	protected Optional<ExecutionMode> getExecutionModeFromAnnotation(AnnotatedElement element) {
		// @formatter:off
		return findAnnotation(element, Execution.class)
				.map(Execution::value)
				.map(JupiterTestDescriptor::toExecutionMode);
		// @formatter:on
	}

	public static ExecutionMode toExecutionMode(org.junit.jupiter.api.parallel.ExecutionMode mode) {
		switch (mode) {
			case CONCURRENT:
				return ExecutionMode.CONCURRENT;
			case SAME_THREAD:
				return ExecutionMode.SAME_THREAD;
		}
		throw new JUnitException("Unknown ExecutionMode: " + mode);
	}

	protected Set<ExclusiveResource> getExclusiveResourcesFromAnnotation(AnnotatedElement element) {
		// @formatter:off
		return findRepeatableAnnotations(element, ResourceLock.class).stream()
				.map(resource -> new ExclusiveResource(resource.value(), toLockMode(resource.mode())))
				.collect(toSet());
		// @formatter:on
	}

	private static LockMode toLockMode(ResourceAccessMode mode) {
		switch (mode) {
			case READ:
				return LockMode.READ;
			case READ_WRITE:
				return LockMode.READ_WRITE;
		}
		throw new JUnitException("Unknown ResourceAccessMode: " + mode);
	}

	@Override
	public SkipResult shouldBeSkipped(JupiterEngineExecutionContext context) throws Exception {
		context.getThrowableCollector().assertEmpty();
		ConditionEvaluationResult evaluationResult = conditionEvaluator.evaluate(context.getExtensionRegistry(),
			context.getConfiguration(), context.getExtensionContext());
		return toSkipResult(evaluationResult);
	}

	private SkipResult toSkipResult(ConditionEvaluationResult evaluationResult) {
		if (evaluationResult.isDisabled()) {
			return SkipResult.skip(evaluationResult.getReason().orElse("<unknown>"));
		}
		return SkipResult.doNotSkip();
	}

	/**
	 * Must be overridden and return a new context so cleanUp() does not accidentally close the parent context.
	 */
	@Override
	public abstract JupiterEngineExecutionContext prepare(JupiterEngineExecutionContext context) throws Exception;

	@Override
	public void cleanUp(JupiterEngineExecutionContext context) throws Exception {
		context.close();
	}

	/**
	 * Execute the supplied {@link Executable} and
	 * {@linkplain ExceptionUtils#throwAsUncheckedException mask} any
	 * exception thrown as an unchecked exception.
	 *
	 * @param executable the {@code Executable} to execute
	 * @see ExceptionUtils#throwAsUncheckedException(Throwable)
	 */
	protected void executeAndMaskThrowable(Executable executable) {
		try {
			executable.execute();
		}
		catch (Throwable throwable) {
			ExceptionUtils.throwAsUncheckedException(throwable);
		}
	}

}
