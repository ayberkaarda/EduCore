package com.educore.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Runs tasks on separate threads that are released together by a barrier. */
final class Concurrently {

    /** Either the task's value or what it threw. */
    record Result<T>(T value, Throwable error) {

        boolean succeeded() {
            return error == null;
        }

        /** The problem code of an {@link AuthProblemException}, otherwise fails the test. */
        String problemCode() {
            if (error instanceof AuthProblemException problem) {
                return problem.code();
            }
            throw new AssertionError("Expected an AuthProblemException, got " + error, error);
        }
    }

    private Concurrently() {
    }

    @SafeVarargs
    static <T> List<Result<T>> run(Callable<? extends T>... tasks) throws Exception {
        return run(List.of(tasks));
    }

    static <T> List<Result<T>> run(List<? extends Callable<? extends T>> tasks) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(tasks.size());
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Result<T>>> futures = new ArrayList<>();
            for (Callable<? extends T> task : tasks) {
                futures.add(executor.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    try {
                        return new Result<T>(task.call(), null);
                    } catch (Exception e) {
                        return new Result<T>(null, e);
                    }
                }));
            }
            List<Result<T>> results = new ArrayList<>();
            for (Future<Result<T>> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
