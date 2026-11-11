package io.vitalstream.clinical.application;

/** Port: full-text alert search (Elasticsearch). */
public interface AlertSearch {

  PageResult<AlertView> search(AlertFilter filter);
}
