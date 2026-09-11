(ns forestry.registry-test
  (:require [clojure.test :refer [deftest is]]
            [forestry.registry :as r]))

;; ----------------------------- stand-verified? -----------------------------

(deftest stand-is-verified-when-flagged
  (is (true? (r/stand-verified? {:id "s1" :verified? true}))))

(deftest stand-is-not-verified-when-false-or-missing
  (is (false? (r/stand-verified? {:id "s1" :verified? false})))
  (is (false? (r/stand-verified? {:id "s1"}))))

;; ----------------------------- stand-immature-for-harvest? -----------------------------

(deftest young-stand-is-immature
  (is (true? (r/stand-immature-for-harvest? {:age-years 8}))))

(deftest old-enough-stand-is-not-immature
  (is (false? (r/stand-immature-for-harvest? {:age-years 35})))
  (is (false? (r/stand-immature-for-harvest? {:age-years 20}))
      "exactly at threshold is mature, not immature"))

(deftest missing-age-is-not-flagged-immature
  (is (false? (r/stand-immature-for-harvest? {}))))

;; ----------------------------- health-status-valid? -----------------------------

(deftest known-health-statuses-are-valid
  (doseq [s [:healthy :concern :critical]]
    (is (r/health-status-valid? s))))

(deftest fabricated-health-status-is-invalid
  (is (not (r/health-status-valid? :thriving-fabricated)))
  (is (not (r/health-status-valid? nil))))

;; ----------------------------- order-total / order-total-matches-claim? -----------------------------

(deftest order-total-is-a-flat-sum-of-line-items
  (is (= 1750.0 (r/order-total {:items [{:qty 500 :unit-cost 2.0}
                                        {:qty 500 :unit-cost 1.5}]}))))

(deftest order-total-empty-items-is-zero
  (is (= 0.0 (r/order-total {:items []})))
  (is (= 0.0 (r/order-total {}))))

(deftest matches-when-claim-equals-recompute
  (is (r/order-total-matches-claim?
       {:items [{:qty 100 :unit-cost 7.5}] :claimed-total 750.0})))

(deftest mismatches-when-claim-differs-from-recompute
  (is (not (r/order-total-matches-claim?
            {:items [{:qty 100 :unit-cost 7.5}] :claimed-total 1000.0}))))

(deftest missing-claimed-total-never-matches
  (is (not (r/order-total-matches-claim? {:items [{:qty 1 :unit-cost 1.0}]}))))

;; ----------------------------- order-exceeds-threshold? -----------------------------

(deftest order-below-threshold-does-not-exceed
  (is (not (r/order-exceeds-threshold? {:items [{:qty 500 :unit-cost 2.0}]}))))

(deftest order-above-threshold-exceeds
  (is (r/order-exceeds-threshold? {:items [{:qty 2 :unit-cost 4000.0}]})))

;; ----------------------------- register-field-operation -----------------------------

(deftest field-operation-is-a-draft-not-a-real-dispatch
  (let [result (r/register-field-operation "op-1" "stand-001" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest field-operation-assigns-operation-number
  (let [result (r/register-field-operation "op-1" "stand-001" 7)]
    (is (= (get result "operation_number") "FOP-000007"))
    (is (= (get-in result ["record" "operation_id"]) "op-1"))
    (is (= (get-in result ["record" "stand_id"]) "stand-001"))
    (is (= (get-in result ["record" "kind"]) "field-operation-schedule-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest field-operation-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-field-operation "" "stand-001" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-field-operation "op-1" "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-field-operation "op-1" "stand-001" -1))))

;; ----------------------------- register-supply-order -----------------------------

(deftest supply-order-is-a-draft-not-a-real-purchase
  (let [result (r/register-supply-order "order-1" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest supply-order-assigns-order-number
  (let [result (r/register-supply-order "order-1" 7)]
    (is (= (get result "order_number") "ORD-000007"))
    (is (= (get-in result ["record" "order_id"]) "order-1"))
    (is (= (get-in result ["record" "kind"]) "supply-order-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest supply-order-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-supply-order "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-supply-order "order-1" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-field-operation "op-1" "stand-001" 0)
        hist (r/append [] c1)
        c2 (r/register-field-operation "op-2" "stand-001" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "FOP-000000" (get-in hist2 [0 "record_id"])))
    (is (= "FOP-000001" (get-in hist2 [1 "record_id"])))))
