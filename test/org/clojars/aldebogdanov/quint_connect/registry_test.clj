(ns org.clojars.aldebogdanov.quint-connect.registry-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.clojars.aldebogdanov.quint-connect.itf :as itf]
            [org.clojars.aldebogdanov.quint-connect.registry :as registry]
            [org.clojars.aldebogdanov.quint-connect.replay :as replay]
            [org.clojars.aldebogdanov.quint-connect.fixtures.bank :as bank]
            [org.clojars.aldebogdanov.quint-connect.fixtures.forms :as forms]
            [org.clojars.aldebogdanov.quint-connect.fixtures.restart :as restart]
            [org.clojars.aldebogdanov.quint-connect.fixtures.two-specs :as two-specs]))

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

(deftest an-arglist-that-cannot-name-picks-is-rejected
  ;; All three of these used to resolve. keyword returns nil rather than
  ;; throwing on a destructuring form, so the pick name became nil, the lookup
  ;; returned nil, and the handler ran on nils — a divergence with nothing
  ;; attached to it saying why.
  (testing "destructuring, which is the :actions calling convention and not this one"
    (let [e (try (registry/resolve-driver
                  {:scan [(fixture :destructured)]})
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :bad-arglist (:quint/error (ex-data e))))
      (is (str/includes? (ex-message e) "transfer"))
      (is (str/includes? (ex-message e) ":actions")
          "the message must name where a picks-map handler does belong")))

  (testing "a rest parameter"
    (let [e (try (registry/resolve-driver
                  {:scan [(fixture :variadic)]})
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :bad-arglist (:quint/error (ex-data e))))
      (is (str/includes? (ex-message e) ":quint/args"))))

  (testing "no :arglists at all, which is what def + fn leaves behind"
    (let [e (try (registry/resolve-driver
                  {:scan [(fixture :no-arglists)]})
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :bad-arglist (:quint/error (ex-data e))))
      (is (str/includes? (ex-message e) "defn")
          "0 arities is the symptom; def + fn is the cause")))

  (testing "and a var that holds no function is told so, not counted as arities"
    (let [e (try (registry/resolve-driver
                  {:scan [(fixture :not-a-function)]})
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :bad-arglist (:quint/error (ex-data e))))
      (is (str/includes? (ex-message e) "not a function")))))

(deftest a-state-annotation-that-would-go-unread-is-rejected
  (testing "an unknown key, which is the misspelling that supplied nil"
    (let [e (try (registry/resolve-driver
                  {:scan [(fixture :state-unknown-key)]})
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :bad-state-spec (:quint/error (ex-data e))))
      (is (str/includes? (ex-message e) ":variable") "name the key that was ignored")))

  (testing "no :var, so no spec variable is named"
    (let [e (try (registry/resolve-driver
                  {:scan [(fixture :state-no-var)]})
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :bad-state-spec (:quint/error (ex-data e))))
      (is (str/includes? (ex-message e) ":quint/state :balances")
          "the message must show the form that works")))

  (testing "and a path that is not a vector, which threw IllegalArgumentException"
    (let [e (try (registry/resolve-driver
                  {:scan [(fixture :state-bad-path)]})
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :bad-state-spec (:quint/error (ex-data e))))
      (is (str/includes? (ex-message e) "vector of keys")))))

(deftest args-may-build-an-argument-out-of-picks
  ;; A function whose own signature takes a map could not be annotated at all
  ;; before: positional binding has no name to bind, and :actions meant moving
  ;; the mapping away from the code it describes.
  (let [d (registry/resolve-driver
           {:scan [(fixture :args-map)]})]
    (testing "one argument, built from three picks under different names"
      (is (= ["a" "b" 5]
             ((get-in d [:actions "transfer" :fn]) {:src "a" :dst "b" :amt 5}))))
    (testing "and the two entry forms compose"
      (is (= ["a" 5]
             ((get-in d [:actions "credit" :fn]) {:who "a" :amt 5}))))
    (testing "the pick names are recorded, so replay can hold the trace to them"
      (is (= #{:src :dst :amt} (get-in d [:actions "transfer" :needs])))
      (is (= #{:who :amt} (get-in d [:actions "credit" :needs])))
      (is (= :quint/args (get-in d [:actions "transfer" :needs-from]))))))

(deftest the-picks-a-handler-needs-are-recorded-from-either-source
  ;; A parameter misspelled against the spec binds nil exactly as a misspelled
  ;; :quint/args does, so both are held to the trace. :needs-from is what
  ;; decides which annotation the failure sends you to.
  (let [d (registry/resolve-driver
           {:scan [(fixture :bank)]})]
    (testing "derived from the parameter list"
      (is (= #{:who :amount} (get-in d [:actions "deposit" :needs])))
      (is (= :arglist (get-in d [:actions "deposit" :needs-from]))))
    (testing "written into :quint/args"
      (is (= #{:who :amount} (get-in d [:actions "withdraw" :needs])))
      (is (= :quint/args (get-in d [:actions "withdraw" :needs-from]))))
    (testing "and an _-prefixed parameter asks for nothing"
      ;; overdraft is [_who _amount]. getting-started §3 documents those
      ;; arriving nil, so they must not become a demand on the trace -- and
      ;; bank_run_0 fires overdraft, so replaying it is the whole-loop proof.
      (is (= #{} (get-in d [:actions "overdraft" :needs])))
      (is (:ok? (replay/run-trace d (trace "bank_run_0.itf.json")))))))

(deftest a-quint-args-that-cannot-be-read-is-rejected
  (testing "not a vector, which threw IllegalArgumentException at replay"
    (let [e (try (registry/resolve-driver
                  {:scan [(fixture :args-not-a-vector)]})
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :bad-args (:quint/error (ex-data e))))
      (is (str/includes? (ex-message e) "must be a vector"))))

  (testing "a map entry whose value does not name a pick"
    (let [e (try (registry/resolve-driver
                  {:scan [(fixture :args-bad-entry)]})
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :bad-args (:quint/error (ex-data e))))
      (is (str/includes? (ex-message e) "{:from :src}")
          "the message must show the form that works")))

  (testing "an entry that is neither a pick name nor a map"
    (let [e (try (registry/resolve-driver
                  {:scan [(fixture :args-bare-entry)]})
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :bad-args (:quint/error (ex-data e)))))))

(deftest a-pick-whose-value-is-a-record-may-be-destructured
  ;; Two different intentions produce the same arglist: destructuring the picks
  ;; map, which is :actions' convention, and destructuring one pick whose value
  ;; is a record, which :quint/args has always covered. Nothing can tell them
  ;; apart from the arglist alone, so :bad-arglist names both.
  (let [d (registry/resolve-driver
           {:scan [(fixture :record-pick)]})
        h (get-in d [:actions "recv"])]
    (is (= ["a" 1] ((:fn h) {:m {:from "a" :body 1}}))
        "the destructuring applies to the pick's value, not to the picks map")))

(deftest a-whole-state-reader-takes-a-path-too
  ;; :* supplies every variable at once, and that map is as likely to sit
  ;; nested inside a system map as a single variable is.
  (let [d (registry/resolve-driver
           {:scan [(fixture :star-nested)]})
        r (first (:readers d))]
    (is (= :* (:supplies r)))
    (is (= {:balances {"alice" 1} :pending 2} ((:fn r)))
        "the path is applied, and the surrounding system map is not compared")))

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

;; --- :quint/driver, when one namespace serves two specs --------------------

(deftest driver-scope-splits-a-shared-namespace
  (testing "each driver gets its own init"
    (let [l (registry/resolve-driver {:name :ledger :scan [(fixture :two-specs)]})
          c (registry/resolve-driver {:name :cache  :scan [(fixture :two-specs)]})]
      (is (= #'two-specs/open-ledger! (get-in l [:init :var])))
      (is (= #'two-specs/open-cache!  (get-in c [:init :var])))))

  (testing "an unscoped annotation belongs to both"
    (doseq [n [:ledger :cache]]
      (let [d (registry/resolve-driver {:name n :scan [(fixture :two-specs)]})]
        (is (contains? (:actions d) "audit")
            "unannotated vars keep working, which is what makes the key optional"))))

  (testing "and a set names several"
    (doseq [n [:ledger :cache]]
      (is (contains? (:actions (registry/resolve-driver
                                {:name n :scan [(fixture :two-specs)]}))
                     "post")))))

(deftest driver-scope-is-what-stops-it-being-a-duplicate
  ;; The same namespace, asked for a driver neither init claims: both are
  ;; filtered out, so there is no init at all rather than a collision.
  (let [d (registry/resolve-driver {:name :neither :scan [(fixture :two-specs)]})]
    (is (nil? (:init d)))
    (is (contains? (:actions d) "audit") "the unscoped ones still arrive")))

(deftest a-scoped-annotation-without-a-driver-name-is-rejected
  ;; Silently ignoring the scope would pick an init by accident, which is the
  ;; failure mode this whole layer exists to prevent.
  (let [e (try (registry/resolve-driver {:scan [(fixture :two-specs)]})
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (= :unnamed-driver (:quint/error (ex-data e))))
    (is (str/includes? (ex-message e) ":name"))
    (is (str/includes? (ex-message e) ":quint/driver"))))

(deftest scoping-does-not-mask-a-real-duplicate
  ;; Two inits that both claim the same driver are still a collision.
  (is (= :duplicate-init
         (error-of #(registry/resolve-driver
                     {:name :bank
                      :scan [(fixture :bank) (fixture :restart)]})))))

(deftest driver-scope-follows-key-ns
  ;; The sixth key moves with the other five, or it would be the per-key
  ;; override 0007 refused.
  (is (= :unnamed-driver
         (error-of #(registry/resolve-driver
                     {:scan [(fixture :two-specs-keyed)] :key-ns 'acme.mbt})))))
