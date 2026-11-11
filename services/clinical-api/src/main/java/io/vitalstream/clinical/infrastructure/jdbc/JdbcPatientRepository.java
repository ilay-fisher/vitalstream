package io.vitalstream.clinical.infrastructure.jdbc;

import io.vitalstream.clinical.application.PatientRepository;
import io.vitalstream.clinical.domain.Patient;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads the {@code clinical.patients} table seeded by the infrastructure workstream. */
@Repository
public class JdbcPatientRepository implements PatientRepository {

  private final JdbcClient jdbc;

  public JdbcPatientRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<Patient> findAll() {
    return jdbc.sql("SELECT id, name, bed, ward FROM clinical.patients ORDER BY id")
        .query(JdbcPatientRepository::map)
        .list();
  }

  @Override
  public Optional<Patient> findById(String id) {
    return jdbc.sql("SELECT id, name, bed, ward FROM clinical.patients WHERE id = :id")
        .param("id", id)
        .query(JdbcPatientRepository::map)
        .optional();
  }

  private static Patient map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new Patient(
        rs.getString("id"), rs.getString("name"), rs.getString("bed"), rs.getString("ward"));
  }
}
