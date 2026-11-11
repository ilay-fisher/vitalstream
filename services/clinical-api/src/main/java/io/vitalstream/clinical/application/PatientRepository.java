package io.vitalstream.clinical.application;

import io.vitalstream.clinical.domain.Patient;
import java.util.List;
import java.util.Optional;

/** Port: patient master data (Postgres {@code clinical.patients}). */
public interface PatientRepository {

  List<Patient> findAll();

  Optional<Patient> findById(String id);
}
