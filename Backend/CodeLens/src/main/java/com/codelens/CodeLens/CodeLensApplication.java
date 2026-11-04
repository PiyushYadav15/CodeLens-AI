package com.codelens.CodeLens;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * ─── @EnableAsync ────────────────────────────────────────────────────────────
 * Activates Spring's asynchronous method execution capability.
 *
 * Without this annotation, every @Async annotation in the app is silently
 * IGNORED — methods still run, but synchronously on the calling thread.
 * This is a common beginner gotcha: "why isn't my @Async running in the
 * background?" — check that @EnableAsync is present here.
 *
 * What this actually does:
 *   Spring creates a proxy around any bean that has @Async methods.
 *   When another bean calls one of those methods, the proxy intercepts the
 *   call, submits the real method to a thread pool, and returns immediately.
 *   The thread pool is configured in application.properties under
 *   spring.task.execution.pool.*
 * ─────────────────────────────────────────────────────────────────────────────
 */
@EnableAsync
@SpringBootApplication
public class CodeLensApplication {

	public static void main(String[] args) {
		SpringApplication.run(CodeLensApplication.class, args);
	}

}
