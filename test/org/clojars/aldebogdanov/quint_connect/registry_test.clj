(ns org.clojars.aldebogdanov.quint-connect.registry-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.clojars.aldebogdanov.quint-connect.itf :as itf]
            [org.clojars.aldebogdanov.quint-connect.registry :as registry]
            [org.clojars.aldebogdanov.quint-connect.replay :as replay]
            [org.clojars.aldebogdanov.quint-connect.fixtures.bank :as bank]
            [org.clojars.aldebogdanov.quint-connect.fixtures.forms :as forms]
            [org.clojars.aldebogdanov.quint-connect.fixtures.restart :as restart]))

(defn- fixture
  "A fixture namespace, by its last segment. The full names run to 47
  characters, which wraps every :scan in this file without this."
  [n]
  (symbol (str "org.clojars.aldebogdanov.quint-connect.fixtures." (name n))))

(defn- error-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:quint/error (ex-data e)))))

(defn- trace [name]
  (itf/itf->trace (itf/json->itf (slurp (str "dev/fixtures/" name)))))

;; --- the payoff: annotations produce a driver replay already accepts -------

(deftest scanned-driver-replays-the-committed-trace
  (let [d (registry/resolve-driver
           {:scan [(fixture :bank)]})
        r (replay/run-trace d (trace "bank_run_0.itf.json"))]
    (is (:ok? r))
    (is (= 5 (:steps r)))
    (is (= #{"withdraw"} (get-in r [:coverage :unused])))))

(deftest resolved-shape-is-what-m2-consumes
  (let [d (registry/resolve-driver
           {:scan [(fixture :bank)]})]
    (is (= #{"deposit" "withdraw" "overdraft"} (set (keys (:actions d)))))
    (is (= #'bank/deposit (get-in d [:actions "deposit" :var])))
    (is (= #'bank/reset-app! (get-in d [:init :var])))
    (is (= 2 (count (:readers d))))
    (is (every? (every-pred :fn :var) (:readers d)))))

(deftest picks-bind-by-parameter-name
  (let [d (registry/resolve-driver
           {:scan [(fixture :bank)]})]
    (bank/reset-app!)
    ((get-in d [:actions "deposit" :fn]) {:amount 7 :who "alice"})
    (is (= 7 (get @bank/accounts "alice")) "bound by name, not by position in the map")))

(deftest quint-args-overrides-arglists
  (let [d (registry/resolve-driver
           {:scan [(fixture :bank)]})]
    (bank/reset-app!)
    ;; withdraw's parameters are [account n]; :quint/args says [:who :amount]
    ((get-in d [:actions "withdraw" :fn]) {:who "alice" :amount 3})
    (is (= -3 (get @bank/accounts "alice")))))

;; --- reader forms ----------------------------------------------------------

(deftest reader-forms
  (let [d       (registry/resolve-driver
                 {:scan [(fixture :forms)]})
        readers (:readers d)
        state   (reduce merge {} (map #((:fn %)) readers))]
    (testing ":path reads through a nested value"
      (is (= "boom" (:lastError state))))
    (testing ":* supplies several variables at once"
      (is (= {"alice" 1} (:balances state)))
      (is (= 2 (:pending state))))
    (testing "halt and private vars are found"
      (is (= #'forms/stop (get-in d [:halt :var])))
      (is (contains? (:actions d) "audit") "ns-interns sees defn-"))))

(deftest ireference-metadata-is-read
  ;; last-error carries its annotation on the atom, not on the var.
  (let [d (registry/resolve-driver
           {:scan [(fixture :bank)]})
        supplied (into #{} (mapcat #(keys ((:fn %)))) (:readers d))]
    (is (= #{:balances :lastError} supplied))))

;; --- the driver map wins ---------------------------------------------------

(deftest driver-map-overrides-the-scan
  (let [called (atom nil)
        d (registry/resolve-driver
           {:scan    [(fixture :bank)]
            :actions {"deposit" (fn [picks] (reset! called picks))}
            :state   {:lastError (fn [] "from-the-map")}})]
    ((get-in d [:actions "deposit" :fn]) {:who "bob"})
    (is (= {:who "bob"} @called) "map handlers take the picks map whole")
    (is (nil? (get-in d [:actions "deposit" :var])) "no var: it came from the map")
    (is (= 2 (count (:readers d))) "the scanned :lastError reader was replaced")
    (is (= "from-the-map"
           (:lastError (reduce merge {} (map #((:fn %)) (:readers d))))))))

(deftest passthrough-keys-survive
  (let [d (registry/resolve-driver
           {:scan [(fixture :bank)]
            :spec "spec/bank.qnt" :main "bankTest" :init-action "init"
            :ignore #{:lastError} :compare {:balances =}})]
    (is (= "spec/bank.qnt" (:spec d)))
    (is (= "bankTest" (:main d)))
    (is (= "init" (:init-action d)))
    (is (= #{:lastError} (:ignore d)))
    (is (fn? (get-in d [:compare :balances])))
    (is (nil? (:scan d)) "consumed, not passed on")))

;; --- validation ------------------------------------------------------------

(deftest empty-scan-names-the-defn-form-trap
  (let [e (try (registry/resolve-driver
                {:scan [(fixture :bare)]})
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (= :empty-scan (:quint/error (ex-data e))))
    (is (str/includes? (ex-message e) "(defn ...)")
        "the message must name the cause, not just the symptom")))

(deftest ambiguous-arity-is-rejected
  (is (= :ambiguous-arity
         (error-of #(registry/resolve-driver
                     {:scan [(fixture :ambiguous)]})))))

(deftest duplicate-action-is-rejected
  (is (= :duplicate-action
         (error-of #(registry/resolve-driver
                     {:scan [(fixture :duplicate)]})))))

(deftest duplicate-state-is-rejected
  (let [e (try (registry/resolve-driver
                {:scan [(fixture :duplicate-state)]})
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (= :duplicate-state (:quint/error (ex-data e))))
    (testing "the message names both vars, or it cannot be acted on"
      (is (str/includes? (ex-message e) "from-an-atom"))
      (is (str/includes? (ex-message e) "from-a-getter")))
    (is (= 2 (count (:vars (ex-data e)))))))

(deftest duplicate-init-is-rejected
  ;; Two in one namespace: scan-ns used to keep whichever ns-interns yielded
  ;; last, so the other reset simply never ran.
  (let [e (try (registry/resolve-driver
                {:scan [(fixture :duplicate-init)]})
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (= :duplicate-init (:quint/error (ex-data e))))
    (is (str/includes? (ex-message e) ":quint/init") "the message names the key")
    (testing "and both vars, or it cannot be acted on"
      (is (str/includes? (ex-message e) "open!"))
      (is (str/includes? (ex-message e) "reopen!"))
      (is (= 2 (count (:vars (ex-data e))))))))

(deftest duplicate-lifecycle-across-namespaces-is-rejected
  (testing "init, the collision a :scan list grows into"
    (is (= :duplicate-init
           (error-of #(registry/resolve-driver
                       {:scan [(fixture :bank) (fixture :restart)]})))))
  (testing "halt, the same way"
    (is (= :duplicate-halt
           (error-of #(registry/resolve-driver
                       {:scan [(fixture :forms) (fixture :restart)]}))))))

(deftest one-of-each-still-resolves
  ;; The guard counts init and halt separately, so a namespace declaring both
  ;; is the ordinary case and not a collision.
  (let [d (registry/resolve-driver
           {:scan [(fixture :restart)]})]
    (is (= #'restart/start! (get-in d [:init :var])))
    (is (= #'restart/stop! (get-in d [:halt :var])))))

(deftest duplicate-action-message-names-both-vars
  (let [e (try (registry/resolve-driver
                {:scan [(fixture :duplicate)]})
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (str/includes? (ex-message e) "deposit-one"))
    (is (str/includes? (ex-message e) "deposit-two"))))

;; --- key-ns ----------------------------------------------------------------

(deftest key-ns-moves-the-whole-vocabulary
  (testing "the default qualifier finds nothing under another one"
    (is (= :empty-scan
           (error-of #(registry/resolve-driver
                       {:scan [(fixture :bank)]
                        :key-ns 'acme.mbt})))))
  (testing "and the scan follows :key-ns when the annotations do"
    (is (contains? (:actions (registry/resolve-driver
                              {:scan [(fixture :keyed)]
                               :key-ns 'acme.mbt}))
                   "deposit"))))

(deftest registry-is-rebuilt-every-call
  (let [a (registry/resolve-driver {:scan [(fixture :bank)]})
        b (registry/resolve-driver {:scan [(fixture :bank)]})]
    (is (not (identical? (:actions a) (:actions b))))))
