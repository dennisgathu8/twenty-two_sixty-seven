(ns bwr.store.query
  "Bi-temporal storage operations, transaction helpers, and Datalog queries for XTDB."
  (:require [xtdb.api :as xt]
            [bwr.store.schema :as schema]))

(set! *warn-on-reflection* true)

;; ============================================================================
;; Transactions & Mutations
;; ============================================================================

(defn- build-put-op
  "Builds an XTDB put operation vector, validating schema and setting valid-time if given."
  ([doc]
   (let [validated (schema/validate-entity! doc)]
     [::xt/put validated]))
  ([doc valid-time]
   (let [validated (schema/validate-entity! doc)]
     [::xt/put validated valid-time]))
  ([doc valid-time end-valid-time]
   (let [validated (schema/validate-entity! doc)]
     [::xt/put validated valid-time end-valid-time])))

(defn transact!
  "Validates and transacts a collection of documents into the XTDB node.
   Awaits transaction completion before returning the transaction receipt."
  [node docs]
  (let [ops (mapv build-put-op docs)
        tx (xt/submit-tx node ops)]
    (xt/await-tx node tx)))

(defn transact-at!
  "Validates and transacts documents into XTDB with a specified valid-time.
   Awaits transaction completion."
  [node docs ^java.util.Date valid-time]
  (let [ops (mapv #(build-put-op % valid-time) docs)
        tx (xt/submit-tx node ops)]
    (xt/await-tx node tx)))

;; ============================================================================
;; Bi-temporal Database Snapshots
;; ============================================================================

(defn db-at
  "Returns a bi-temporal database snapshot from the XTDB node.
   Arities:
     [node]                           - latest valid-time and latest tx-time
     [node valid-time]                - snapshot at given valid-time (java.util.Date or Instant)
     [node valid-time tx-time]        - snapshot as of both valid-time and tx-time (as-of query)"
  ([node]
   (xt/db node))
  ([node valid-time]
   (xt/db node (if (instance? java.time.Instant valid-time)
                 (java.util.Date/from ^java.time.Instant valid-time)
                 valid-time)))
  ([node valid-time tx-time]
   (xt/db node
          (if (instance? java.time.Instant valid-time)
            (java.util.Date/from ^java.time.Instant valid-time)
            valid-time)
          (if (instance? java.time.Instant tx-time)
            (java.util.Date/from ^java.time.Instant tx-time)
            tx-time))))

(defn entity
  "Retrieves an entity map by its ID (:xt/id) from a db snapshot."
  [db eid]
  (xt/entity db eid))

(defn entity-history
  "Returns the complete historical timeline of an entity document over time.
   Accepts :asc or :desc sort order and options map {:with-docs? true}."
  ([node eid]
   (entity-history node eid :asc {:with-docs? true}))
  ([node eid sort-order]
   (entity-history node eid sort-order {:with-docs? true}))
  ([node eid sort-order opts]
   (xt/entity-history (if (satisfies? xt/DBProvider node) (xt/db node) node)
                      eid
                      sort-order
                      opts)))

(defn q
  "Executes a Datalog query against the database snapshot."
  [db query & args]
  (apply xt/q db query args))

;; ============================================================================
;; Domain Queries
;; ============================================================================

(defn find-match
  "Returns the match entity for the given match-id."
  [db match-id]
  (entity db match-id))

(defn find-all-matches
  "Returns all match entities in the store, ordered by kickoff time."
  [db]
  (let [results (xt/q db
                      '{:find [(pull ?m [*])]
                        :where [[?m :match/id _]]})]
    (->> results
         (mapv first)
         (sort-by :match/kickoff))))

(defn find-matches-by-tournament
  "Returns all matches for a given tournament ID."
  [db tournament-id]
  (let [results (xt/q db
                      '{:find [(pull ?m [*])]
                        :in [?tid]
                        :where [[?m :match/tournament ?tid]]}
                      tournament-id)]
    (mapv first results)))

(defn find-events-by-match
  "Returns all events for a given match ID, ordered by minute."
  [db match-id]
  (let [results (xt/q db
                      '{:find [(pull ?e [*])]
                        :in [?mid]
                        :where [[?e :event/match ?mid]]}
                      match-id)]
    (->> results
         (mapv first)
         (sort-by :event/minute))))

(defn find-stoppages-by-match
  "Returns all stoppages for a given match ID, ordered by half and clock minute."
  [db match-id]
  (let [results (xt/q db
                      '{:find [(pull ?s [*])]
                        :in [?mid]
                        :where [[?s :stoppage/match ?mid]]}
                      match-id)]
    (->> results
         (mapv first)
         (sort-by (juxt :stoppage/half :stoppage/clock-minute)))))

(defn find-events-in-window
  "Returns all match events for match-id whose minute falls within [start-minute, end-minute]."
  [db match-id start-minute end-minute]
  (let [results (xt/q db
                      '{:find [(pull ?e [*])]
                        :in [?mid ?start ?end]
                        :where [[?e :event/match ?mid]
                                [?e :event/minute ?m]
                                [(>= ?m ?start)]
                                [(<= ?m ?end)]]}
                      match-id start-minute end-minute)]
    (->> results
         (mapv first)
         (sort-by :event/minute))))

(defn find-recommendations-for-match
  "Returns all generated recommendations for a given match ID."
  [db match-id]
  (let [results (xt/q db
                      '{:find [(pull ?r [*])]
                        :in [?mid]
                        :where [[?r :recommendation/match ?mid]]}
                      match-id)]
    (mapv first results)))

(defn find-recommendations-for-team
  "Returns all recommendations across matches for a specific team."
  [db team-name]
  (let [results (xt/q db
                      '{:find [(pull ?r [*])]
                        :in [?team]
                        :where [[?r :recommendation/team ?team]]}
                      team-name)]
    (mapv first results)))

;; ============================================================================
;; Security Event Audit Trail Queries (§2.4, §9.4)
;; ============================================================================

(defn log-security-event!
  "Logs a validated security event into XTDB storage (§2.4).
   Awaits transaction completion."
  [node event-map]
  (let [evt-id (or (:sec-event/id event-map) (str "sec-evt-" (java.util.UUID/randomUUID)))
        full-evt (assoc event-map
                        :sec-event/id evt-id
                        :sec-event/timestamp (or (:sec-event/timestamp event-map) (java.util.Date.)))
        validated (schema/validate-entity! full-evt)]
    (transact! node [validated])
    validated))

(defn find-security-events
  "Queries security audit trail events from XTDB snapshot.
   Opts can optionally include:
     :type     - filter by keyword event type (e.g. :sec.type/magic-link-generated)
     :identity - filter by identity string"
  ([db] (find-security-events db {}))
  ([db opts]
   (let [results (xt/q db
                       '{:find [(pull ?e [*])]
                         :where [[?e :sec-event/id _]]})
         events (->> results
                     (mapv first)
                     (sort-by :sec-event/timestamp #(compare %2 %1)))]
     (cond->> events
       (:type opts) (filterv #(= (:sec-event/type %) (:type opts)))
       (:identity opts) (filterv #(= (:sec-event/identity %) (:identity opts)))))))
