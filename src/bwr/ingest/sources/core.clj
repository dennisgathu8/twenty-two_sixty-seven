(ns bwr.ingest.sources.core
  "Extensible multi-method dispatch for parsing match records across different data sources.
   New sources are implemented as additions to this multimethod, never modifications.")

(set! *warn-on-reflection* true)

(defmulti parse-record
  "Parses an external source record into a normalized match event map.
   Dispatches on the source keyword (e.g. :source/fbref, :source/wikipedia).
   If parsing succeeds, returns a map conforming to ::bwr.ingest.spec/match-event.
   If parsing fails or raw input is invalid, returns an invalid map or nil."
  (fn [source _raw-record] source))
