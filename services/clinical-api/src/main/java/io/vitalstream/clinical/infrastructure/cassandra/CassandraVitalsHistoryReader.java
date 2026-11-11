package io.vitalstream.clinical.infrastructure.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import io.vitalstream.clinical.application.VitalPoint;
import io.vitalstream.clinical.application.VitalsHistoryReader;
import io.vitalstream.clinical.application.VitalsQuery;
import io.vitalstream.clinical.domain.VitalMetric;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Reads {@code vitals_raw} and {@code vitals_by_minute}. Both tables are partitioned by UTC day, so
 * a range query fans out to one partition per day (the controller caps ranges at 7 days) and the
 * result is capped to protect the API from huge responses.
 */
@Component
public class CassandraVitalsHistoryReader implements VitalsHistoryReader {

  static final int MAX_POINTS = 5_000;

  private static final String RAW_CQL =
      "SELECT observed_at, metric, value, unit, device_id FROM vitals_raw"
          + " WHERE patient_id = ? AND day = ? AND observed_at >= ? AND observed_at <= ?";
  private static final String MINUTE_CQL =
      "SELECT window_start, \"min\", \"max\", \"avg\", \"count\" FROM vitals_by_minute"
          + " WHERE patient_id = ? AND metric = ? AND day = ?"
          + " AND window_start >= ? AND window_start <= ?";

  private final CassandraSessionHolder holder;
  private PreparedStatement rawStatement;
  private PreparedStatement minuteStatement;

  public CassandraVitalsHistoryReader(CassandraSessionHolder holder) {
    this.holder = holder;
  }

  @Override
  public List<VitalPoint> read(VitalsQuery query) {
    CqlSession session = holder.session();
    List<VitalPoint> points =
        query.resolution() == VitalsQuery.Resolution.RAW
            ? readRaw(session, query)
            : readMinute(session, query);
    points.sort(Comparator.comparing(VitalPoint::observedAt).thenComparing(VitalPoint::metric));
    return points.size() > MAX_POINTS
        ? points.subList(points.size() - MAX_POINTS, points.size())
        : points;
  }

  private List<VitalPoint> readRaw(CqlSession session, VitalsQuery query) {
    PreparedStatement statement = raw(session);
    List<VitalPoint> points = new ArrayList<>();
    for (LocalDate day : days(query)) {
      for (Row row :
          session.execute(statement.bind(query.patientId(), day, query.from(), query.to()))) {
        String metric = row.getString("metric");
        if (query.metric() == null || query.metric().name().equals(metric)) {
          points.add(
              VitalPoint.raw(
                  row.getInstant("observed_at"),
                  metric,
                  row.getDouble("value"),
                  row.getString("unit"),
                  row.getString("device_id")));
        }
      }
    }
    return points;
  }

  private List<VitalPoint> readMinute(CqlSession session, VitalsQuery query) {
    PreparedStatement statement = minute(session);
    List<VitalMetric> metrics =
        query.metric() == null ? List.of(VitalMetric.values()) : List.of(query.metric());
    List<VitalPoint> points = new ArrayList<>();
    for (VitalMetric metric : metrics) {
      for (LocalDate day : days(query)) {
        for (Row row :
            session.execute(
                statement.bind(query.patientId(), metric.name(), day, query.from(), query.to()))) {
          points.add(
              VitalPoint.minute(
                  row.getInstant("window_start"),
                  metric.name(),
                  row.getDouble("min"),
                  row.getDouble("max"),
                  row.getDouble("avg"),
                  row.getInt("count")));
        }
      }
    }
    return points;
  }

  private static List<LocalDate> days(VitalsQuery query) {
    List<LocalDate> days = new ArrayList<>();
    LocalDate last = query.to().atOffset(ZoneOffset.UTC).toLocalDate();
    for (LocalDate d = query.from().atOffset(ZoneOffset.UTC).toLocalDate();
        !d.isAfter(last);
        d = d.plusDays(1)) {
      days.add(d);
    }
    return days;
  }

  private synchronized PreparedStatement raw(CqlSession session) {
    if (rawStatement == null) {
      rawStatement = session.prepare(RAW_CQL);
    }
    return rawStatement;
  }

  private synchronized PreparedStatement minute(CqlSession session) {
    if (minuteStatement == null) {
      minuteStatement = session.prepare(MINUTE_CQL);
    }
    return minuteStatement;
  }
}
