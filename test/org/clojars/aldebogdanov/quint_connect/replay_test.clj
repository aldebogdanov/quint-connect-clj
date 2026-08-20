(ns org.clojars.aldebogdanov.quint-connect.replay-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.clojars.aldebogdanov.quint-connect.itf :as itf]
            [org.clojars.aldebogdanov.quint-connect.replay :as replay]
            [org.clojars.aldebogdanov.quint-connect.report :as report]))

;; --- a toy bank, standing in for an application ---------------------------

(def accounts (atom {}))
(def last-error (atom ""))

(defn reset-app! []
  (reset! accounts {"alice" 0 "bob" 0})
  (reset! last-error ""))

(defn deposit! [{:keys [who amount]}]
  (swap! accounts update who + amount)
  (reset! last-error ""))

(defn withdraw! [{:keys [who amount]}]
  (swap! accounts update who - amount)
  (reset! last-error ""))

(defn overdraft! [_]
  (reset! last-error "insufficient funds"))

(defn- driver
  "A resolved driver as M3 will produce one. Overrides are merged on top."
  [& {:as overrides}]
  (merge
   {:actions {"deposit"   {:fn deposit!   :var #'deposit!}
              "withdraw"  {:fn withdraw!  :var #'withdraw!}
              "overdraft" {:fn overdraft! :var #'overdraft!}}
    :readers [{:fn #(hash-map :balances @accounts) :var #'accounts}
              {:fn #(hash-map :lastError @last-error) :var #'last-error}]
    :init    {:fn reset-app! :var #'reset-app!}}
   overrides))

(defn- trace [name]
  (itf/itf->trace (itf/json->itf (slurp (str "dev/fixtures/" name)))))

(defn- error-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:quint/error (ex-data e)))))

;; --- the happy path -------------------------------------------------------

(deftest conforming-implementation-passes
  (let [r (replay/run-trace (driver) (trace "bank_run_0.itf.json"))]
    (is (:ok? r))
    (is (nil? (:failure r)))
    (is (= 5 (:steps r)))
    (is (nil? (report/failure-str r)))
    (testing "coverage counts handlers, not the init lifecycle"
      (is (= {"overdraft" 1 "deposit" 3} (get-in r [:coverage :used])))
      (is (= #{"withdraw"} (get-in r [:coverage :unused]))))))

(deftest both-committed-traces-pass
  (is (every? :ok? (map #(replay/run-trace (driver) (trace %))
                        ["bank_run_0.itf.json" "bank_run_1.itf.json"]))))

;; --- divergence -----------------------------------------------------------

(deftest injected-bug-fails-at-the-diverging-step
  ;; deposit adds one too many; bank_run_0 first deposits at step 2.
  (let [bad (fn [{:keys [who amount]}]
              (swap! accounts update who + amount 1)
              (reset! last-error ""))
        r   (replay/run-trace
             (driver :actions (assoc (:actions (driver)) "deposit" {:fn bad :var #'deposit!}))
             (trace "bank_run_0.itf.json"))
        f   (:failure r)]
    (is (false? (:ok? r)))
    (is (= 2 (:step f)))
    (is (= "deposit" (:action f)))
    (is (= {:who "alice" :amount 39} (:picks f)))
    (is (= #'deposit! (:handler f)))
    (is (= {:balances {"alice" 39 "bob" 0}} (:expected f)))
    (is (= {:balances {"alice" 40 "bob" 0}} (:actual f)))
    (is (= {:balances #'accounts} (:readers f)) "the reader that observed it")
    (is (nil? (:cause f)))
    (is (= 3 (:steps r)) "stopped at the first mismatch")))

(deftest failure-str-names-what-a-human-needs
  (let [bad (fn [_] (swap! accounts update "alice" + 999))
        r   (replay/run-trace
             (driver :actions (assoc (:actions (driver)) "deposit" {:fn bad :var #'deposit!}))
             (trace "bank_run_0.itf.json"))
        s   (report/failure-str r)]
    (is (str/includes? s "step 2"))
    (is (str/includes? s "\"deposit\""))
    (is (str/includes? s ":who \"alice\""))
    (is (str/includes? s "replay-test/deposit!"))
    (is (str/includes? s "replay-test/accounts"))
    (is (str/includes? s "expected"))
    (is (str/includes? s "actual"))))

(deftest init-that-does-not-reset-fails-at-step-0-of-the-second-trace
  ;; The isolation property: trace 0 leaves alice=39/bob=115, and trace 1
  ;; expects 0/0 immediately after init.
  (let [leaky (driver :init {:fn (fn [] (reset! last-error "")) :var #'reset-app!})]
    (is (:ok? (replay/run-trace (driver) (trace "bank_run_0.itf.json"))))
    (let [r (replay/run-trace leaky (trace "bank_run_1.itf.json"))]
      (is (false? (:ok? r)))
      (is (= 0 (:step (:failure r))) "step 0, not somewhere later and mysterious")
      (is (= {:balances {"alice" 0 "bob" 0}} (:expected (:failure r))))
      (is (= {:balances {"alice" 39 "bob" 115}} (:actual (:failure r)))))))

;; --- driver-level options -------------------------------------------------

(deftest ignore-skips-a-variable
  (let [r (replay/run-trace
           (driver :ignore #{:lastError}
                   :actions (assoc (:actions (driver))
                                   "overdraft" {:fn (fn [_] (reset! last-error "wrong")) :var nil}))
           (trace "bank_run_0.itf.json"))]
    (is (:ok? r))))

(deftest compare-overrides-equality
  (let [r (replay/run-trace
           (driver :compare {:lastError (fn [_ _] true)}
                   :actions (assoc (:actions (driver))
                                   "overdraft" {:fn (fn [_] (reset! last-error "wrong")) :var nil}))
           (trace "bank_run_0.itf.json"))]
    (is (:ok? r))))

;; --- broken setups are exceptions, not results ----------------------------

(deftest unknown-action-throws
  (is (= :unknown-action
         (error-of #(replay/run-trace
                     (driver :actions {}
                             :init {:fn reset-app! :var nil})
                     (trace "bank_run_0.itf.json"))))))

(deftest missing-init-throws
  (is (= :no-init
         (error-of #(replay/run-trace (driver :init nil) (trace "bank_run_0.itf.json"))))))

(deftest trace-without-action-metadata-throws
  ;; quint test emits no mbt:: variables; the message must say how to get them.
  (let [t (trace "bank_test_depositThenWithdrawTest.itf.json")
        e (try (replay/run-trace (driver) t)
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (= :unknown-action (:quint/error (ex-data e))))
    (is (str/includes? (ex-message e) "--mbt"))))

(deftest failing-reader-throws
  (is (= :state-read-failed
         (error-of #(replay/run-trace
                     (driver :readers [{:fn (fn [] (throw (RuntimeException. "db down")))
                                        :var #'accounts}])
                     (trace "bank_run_0.itf.json")))))
  (is (= :state-read-failed
         (error-of #(replay/run-trace
                     (driver :readers [{:fn (fn [] :not-a-map) :var #'accounts}])
                     (trace "bank_run_0.itf.json"))))))

(deftest overlapping-readers-are-caught-once-they-have-been-called
  ;; The registry rejects two readers that each name one variable. A :* reader
  ;; names nothing, so an overlap involving one is only visible here — and
  ;; before this it silently resolved, with the winner decided by ns-interns
  ;; hash order rather than by anything the author wrote.
  (let [star  {:fn (fn [] {:balances @accounts :lastError @last-error}) :var #'accounts}
        named {:fn #(hash-map :balances @accounts) :var #'last-error}
        e     (try (replay/run-trace (driver :readers [star named])
                                     (trace "bank_run_0.itf.json"))
                   (catch clojure.lang.ExceptionInfo ex ex))]
    (is (= :duplicate-state (:quint/error (ex-data e))))
    (is (str/includes? (ex-message e) ":balances"))
    (testing "and both readers are named, or it cannot be acted on"
      (is (str/includes? (ex-message e) "accounts"))
      (is (str/includes? (ex-message e) "last-error"))
      (is (= 2 (count (:vars (ex-data e))))))))

(deftest a-driver-map-state-entry-may-still-override-a-reader
  ;; :override? is what keeps the documented precedence working: the driver
  ;; map wins over the scan, and that is not a collision.
  (let [star     {:fn (fn [] {:balances @accounts :lastError @last-error}) :var #'accounts}
        override {:fn #(hash-map :balances @accounts) :var nil
                  :supplies :balances :override? true}
        r        (replay/run-trace (driver :readers [star override])
                                   (trace "bank_run_0.itf.json"))]
    (is (:ok? r) "the override supplies :balances and nothing complains")))

;; --- a throwing handler is a result, not an exception ---------------------

(deftest throwing-handler-is-reported
  (let [boom (fn [_] (throw (IllegalStateException. "account locked")))
        r    (replay/run-trace
              (driver :actions (assoc (:actions (driver)) "overdraft" {:fn boom :var #'overdraft!}))
              (trace "bank_run_0.itf.json"))
        f    (:failure r)]
    (is (false? (:ok? r)))
    (is (= 1 (:step f)))
    (is (= #'overdraft! (:handler f)))
    (is (instance? IllegalStateException (:cause f)))
    (is (str/includes? (report/failure-str r) "account locked"))))

;; --- halt ------------------------------------------------------------------

(deftest halt-runs-even-when-the-trace-fails
  (let [halted (atom 0)
        h      {:fn (fn [] (swap! halted inc)) :var nil}]
    (replay/run-trace (driver :halt h) (trace "bank_run_0.itf.json"))
    (is (= 1 @halted))
    (error-of #(replay/run-trace (driver :halt h :init nil) (trace "bank_run_0.itf.json")))
    (is (= 2 @halted) "including when the setup was broken")))
