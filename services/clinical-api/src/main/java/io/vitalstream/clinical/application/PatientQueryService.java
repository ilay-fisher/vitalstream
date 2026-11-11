package io.vitalstream.clinical.application;

import io.vitalstream.clinical.domain.Patient;
import io.vitalstream.clinical.domain.PatientNotFoundException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Combines Postgres master data, Redis live vitals and Postgres alert counts. */
@Service
public class PatientQueryService {

  private final PatientRepository patients;
  private final LatestVitalsStore latestVitals;
  private final AlertRepository alerts;

  public PatientQueryService(
      PatientRepository patients, LatestVitalsStore latestVitals, AlertRepository alerts) {
    this.patients = patients;
    this.latestVitals = latestVitals;
    this.alerts = alerts;
  }

  public List<PatientView> list() {
    Map<String, Long> openCounts = alerts.openCountsByPatient();
    return patients.findAll().stream()
        .map(p -> view(p, openCounts.getOrDefault(p.id(), 0L)))
        .toList();
  }

  public PatientView get(String id) {
    Patient patient = patients.findById(id).orElseThrow(() -> new PatientNotFoundException(id));
    return view(patient, alerts.openCount(id));
  }

  /** Fails with {@link PatientNotFoundException} unless the patient exists. */
  public void requireExists(String id) {
    if (patients.findById(id).isEmpty()) {
      throw new PatientNotFoundException(id);
    }
  }

  private PatientView view(Patient p, long openAlerts) {
    return new PatientView(
        p.id(), p.name(), p.bed(), p.ward(), latestVitals.latest(p.id()), openAlerts);
  }
}
