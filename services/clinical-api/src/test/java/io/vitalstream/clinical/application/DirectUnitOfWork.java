package io.vitalstream.clinical.application;

import java.util.function.Supplier;

/** Test double that runs work immediately, without a transaction. */
final class DirectUnitOfWork implements UnitOfWork {

  @Override
  public <T> T run(Supplier<T> work) {
    return work.get();
  }
}
