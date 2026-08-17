(ns org.clojars.aldebogdanov.quint-connect.itf-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.clojars.aldebogdanov.quint-connect.itf :as itf]))

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

;; --- a spec that records the action itself --------------------------------

(def ^:private tracked {:action-path [:lastAction] :nondet-path [:lastPick]})

(deftest tracked-variables-drive-a-trace-with-no-mbt
  (let [t (trace "tracked_test_depositThenOverdraftTest.itf.json" tracked)]
    (is (= [:balances :lastError] (:vars t))
        "the spec's bookkeeping is not state the implementation must supply")
    (is (= {:index 1 :action "deposit" :picks {:who "alice" :amount 50}
            :state {:balances {"alice" 50 "bob" 0} :lastError ""}}
           (second (:states t))))
    (is (= "overdraft" (get-in t [:states 3 :action])))
    (is (= {:who "bob" :amount 5} (get-in t [:states 3 :picks])))

    (testing "and without the paths the same file drives nothing"
      (let [u (trace "tracked_test_depositThenOverdraftTest.itf.json")]
        (is (every? #(nil? (:action %)) (:states u)))
        (is (contains? (get-in u [:states 1 :state]) :lastAction))))))

(deftest tracked-variables-and-mbt-agree
  (let [name "tracked_run_0.itf.json"
        m    (:states (trace name))
        p    (:states (trace name tracked))]
    (testing "on a trace carrying both, the paths win and say the same thing"
      (is (= (map :action (rest m)) (map :action (rest p))))
      (is (= (map :picks (rest m)) (map :picks (rest p)))))
    (testing "except at step 0, where mbt:: has None and the spec has a value"
      (is (= {} (:picks (first m))))
      (is (= {:who "" :amount 0} (:picks (first p)))))))

(deftest a-sum-type-action-name-is-reachable-by-path
  (let [t (trace "shapes_0.itf.json" {:action-path [:aStatus :tag]})]
    (is (= "Named" (get-in t [:states 1 :action])))
    (is (not (contains? (get-in t [:states 1 :state]) :aStatus))
        "the whole variable leaves the state, not just the tag")))

(deftest a-path-that-leads-nowhere-is-typed
  (testing "a bare keyword instead of a vector"
    (is (= :bad-decode-path
           (error-of #(trace "tracked_test_depositThenOverdraftTest.itf.json"
                             {:action-path :lastAction})))))

  (testing "a variable that is not there"
    (is (= :bad-decode-path
           (error-of #(trace "bank_run_0.itf.json" {:action-path [:nope]})))))

  (testing "a variable that is not an action name, with the fix in the message"
    (let [e (try (trace "shapes_0.itf.json" {:action-path [:aStatus]})
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :bad-decode-path (:quint/error (ex-data e))))
      (is (str/includes? (ex-message e) ":tag"))))

  (testing "picks that are not a record"
    (is (= :bad-decode-path
           (error-of #(trace "tracked_test_depositThenOverdraftTest.itf.json"
                             (assoc tracked :nondet-path [:lastError])))))))

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
