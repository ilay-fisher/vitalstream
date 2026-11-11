package io.vitalstream.clinical.infrastructure;

import io.vitalstream.clinical.application.UnitOfWork;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link UnitOfWork} backed by a Spring JDBC transaction. */
@Component
public class TransactionalUnitOfWork implements UnitOfWork {

  private final TransactionTemplate template;

  public TransactionalUnitOfWork(TransactionTemplate template) {
    this.template = template;
  }

  @Override
  public <T> T run(Supplier<T> work) {
    return template.execute(status -> work.get());
  }
}
