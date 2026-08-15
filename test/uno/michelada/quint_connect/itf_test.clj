(ns uno.michelada.quint-connect.itf-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [uno.michelada.quint-connect.itf :as itf]))

(defn- fixture [name]
  (itf/json->itf (slurp (str "dev/fixtures/" name))))

(defn- trace
  ([name] (itf/itf->trace (fixture name)))
  ([name opts] (itf/itf->trace (fixture name) opts)))

(defn- error-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:quint/error (ex-data e)))))

(deftest bank-run-decodes
  (let [t (trace "bank_run_0.itf.json")]
    (is (= "bank.qnt" (:source t)))
    (is (= [:balances :lastError] (:vars t)))
    (is (= 5 (count (:states t))))

    (is (= {:index 0 :action "init" :picks {}
            :state {:balances {"alice" 0 "bob" 0} :lastError ""}}
           (first (:states t))))

    (testing "picks unwrapped out of Quint's Some/None"
      (is (= {:index 1 :action "overdraft" :picks {:who "bob" :amount 85}
              :state {:balances {"alice" 0 "bob" 0} :lastError "insufficient funds"}}
             (second (:states t)))))

    (testing "integers are longs, so = works against ordinary values"
      (is (= 39 (get-in t [:states 2 :state :balances "alice"])))
      (is (instance? Long (get-in t [:states 2 :state :balances "alice"]))))))

(deftest vars-come-from-state-keys
  (let [raw (fixture "bank_run_0.itf.json")]
    (is (= 6 (count (get raw "vars"))) "the file's own array has duplicates")
    (is (= [:balances :lastError] (:vars (itf/itf->trace raw))))))

(deftest shapes-decodes-every-encoding
  (let [t       (trace "shapes_0.itf.json")
        [s0 s1] (:states t)]
    (is (= [:aBigInt :aBool :aList :aRec :aSet :aStatus :aTup :anOpt :nested] (:vars t))
        "camelCase kept, not kebab-cased")
    (is (= {:aSet    #{1 2 3}
            :aTup    [7 "seven"]
            :aRec    {:x 1 :y "why"}
            :anOpt   {:tag "Some" :value 5}
            :aStatus {:tag "Busy" :value 2}
            :aBool   true
            :aBigInt 9007199254740993
            :nested  {1 #{"a"} 2 #{}}
            ;; a Quint List is a bare JSON array, with no #tag of its own
            :aList   ["x" "y"]}
           (:state s0)))
    (is (= {} (:picks s0)) "shapes.qnt has no nondet")

    (testing "Some/None in a spec-declared variable is left wrapped"
      (is (= {:tag "None" :value []} (:anOpt (:state s1))))
      (is (= {:tag "Named" :value {:who "bob" :n 3}} (:aStatus (:state s1))))
      (is (= 9007199254740994 (:aBigInt (:state s1))))
      (is (= ["x" "y" "z"] (:aList (:state s1))) "lists decode to vectors"))))

(deftest bigint-backends-agree
  ;; The {s,e,c} reconstruction is right exactly when it produces what the
  ;; typescript backend wrote plainly.
  (let [rust (trace "bigint_rust_0.itf.json")
        ts   (trace "bigint_typescript_0.itf.json")]
    (is (= (:states ts) (:states rust)))
    (is (= {:atBoundary 1000000000000000 :belowBoundary 999999999999999
            :unsafe 9007199254740993}
           (get-in rust [:states 0 :state])))))

(deftest traces-without-mbt-vars-decode
  (let [big (trace "bigint_rust_0.itf.json")
        tst (trace "bank_test_depositThenWithdrawTest.itf.json")]
    (is (= [:atBoundary :belowBoundary :unsafe] (:vars big)))
    (is (every? #(and (nil? (:action %)) (= {} (:picks %))) (:states big)))
    (is (= {:index 0 :action nil :picks {}
            :state {:balances {"alice" 0 "bob" 0} :lastError ""}}
           (first (:states tst))))
    (is (= 50 (get-in tst [:states 1 :state :balances "alice"])))))

(deftest meta-noise-is-dropped
  (let [raw (fixture "bank_run_0.itf.json")
        t   (trace "bank_run_0.itf.json")
        s   (pr-str t)]
    (is (contains? (get raw "#meta") "timestamp"))
    (is (not (str/includes? s "timestamp")))
    (is (not (str/includes? s "Created by Quint")))
    (is (= [0 1 2 3 4] (mapv :index (:states t))) "only #meta.index survives")))

(deftest collision-is-an-error
  (is (= :name-collision (error-of #(trace "collide_0.itf.json"))))
  (let [d (try (trace "collide_0.itf.json")
               (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :n (:key d)))
    (is (= ["collide::left::n" "collide::right::n"] (:names d)))))

(deftest key-fn-resolves-a-collision
  (let [t (trace "collide_0.itf.json"
                 {:key-fn #(keyword (str/join "-" (take-last 2 (str/split % #"::"))))})]
    (is (= [:left-n :right-n] (:vars t)))
    (is (= {:left-n 1 :right-n 100} (get-in t [:states 0 :state])))))

(deftest malformed-input-is-typed
  (is (= :bad-itf (error-of #(itf/json->itf "not json"))))
  (is (= :bad-itf (error-of #(itf/json->itf "[1,2,3]"))))
  (is (= :bad-itf (error-of #(itf/itf->trace {"#meta" {}})))))

;; No recording exists for the three cases below, so each mutates a real
;; fixture rather than inventing JSON.

(deftest unsupported-encoding-is-typed
  (let [bad (assoc-in (fixture "bank_run_0.itf.json")
                      ["states" 0 "bankTest::bank::lastError"]
                      {"#unserializable" "lambda"})]
    (is (= :bad-itf (error-of #(itf/itf->trace bad))))
    (is (str/includes? (try (itf/itf->trace bad)
                            (catch clojure.lang.ExceptionInfo e (ex-message e)))
                       "#unserializable"))))

(deftest unwrapped-pick-is-typed
  (let [bad (assoc-in (fixture "bank_run_0.itf.json")
                      ["states" 1 "mbt::nondetPicks" "who"] "bob")]
    (is (= :bad-itf (error-of #(itf/itf->trace bad))))))

(deftest record-with-sec-fields-is-not-a-number
  (let [rec {"s" {"#bigint" "1"} "e" {"#bigint" "15"} "c" "not-a-chunk-list"}
        ok  (assoc-in (fixture "bank_run_0.itf.json")
                      ["states" 0 "bankTest::bank::lastError"] rec)]
    (is (= {:s 1 :e 15 :c "not-a-chunk-list"}
           (get-in (itf/itf->trace ok) [:states 0 :state :lastError])))))
