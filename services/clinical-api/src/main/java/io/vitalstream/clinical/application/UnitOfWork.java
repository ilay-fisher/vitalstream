package io.vitalstream.clinical.application;

import java.util.function.Supplier;

/**
 * Port: runs work in one database transaction. Keeps application services free of transaction
 * plumbing and lets callers publish side effects only after the commit has returned.
 */
public interface UnitOfWork {

  <T> T run(Supplier<T> work);
}
