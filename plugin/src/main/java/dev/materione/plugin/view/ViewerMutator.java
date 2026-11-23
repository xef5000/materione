package dev.materione.plugin.view;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that mutates a viewer's record ({@link ViewerRecord}) or its kind payload, and so reports itself to
 * the owner-thread guard ({@code ViewerRecord#mutating}). Only the view and GUI runtimes, which run on the viewer's
 * owner thread, may call a marked method; {@code ViewerOwnerThreadTest} holds both halves (views.md sections 5.6, 11):
 * every method that reports to the guard carries this marker, and no class outside those packages calls one.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface ViewerMutator {}
