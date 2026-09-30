(ns bwr.store.seed-test
  "Unit tests for deterministic fixture seeding in bwr.store.seed (§5, §8, §10, §12)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [bwr.store.node :as store-node]
            [bwr.store.query :as query]
            [bwr.store.seed :as seed]))

(def ^:dynamic *node* nil)

(defn with-clean-store [f]
  (let [node (store-node/start-node! {:topology :in-memory})]
    (try
      (binding [*node* node]
        (f))
      (finally
        (store-node/stop-node! node)))))

(use-fixtures :each with-clean-store)

(deftest seed-fixtures-test
  (testing "seed-fixtures! idempotently transacts match M42, stoppages, events, rules, and recommendations"
    (let [summary (seed/seed-fixtures! *node*)]
      (is (= 1 (:matches summary)))
      (is (= 2 (:stoppages summary)))
      (is (= 3 (:events summary)))
      (is (pos? (:rules summary)))
      (is (pos? (:recommendations summary)))

      (let [db (query/db-at *node*)
            match (query/find-match db "M42")
            stoppages (query/find-stoppages-by-match db "M42")
            events (query/find-events-by-match db "M42")
            recs (query/find-recommendations-for-match db "M42")]
        (is (some? match))
        (is (= "France" (:match/home-team match)))
        (is (= "Iraq" (:match/away-team match)))
        (is (= :quality/verified (:match/data-quality match)))

        (is (= 2 (count stoppages)))
        (is (= 3 (count events)))
        (is (pos? (count recs)))

        (let [rec (first recs)]
          (is (= "France" (:recommendation/team rec)))
          (is (seq (:recommendation/evidence rec)))
          (let [evidence-entity (query/entity db (first (:recommendation/evidence rec)))]
            (is (some? evidence-entity))
            (is (= "France" (:event/team evidence-entity)))))))))
