(ns uno.michelada.quint-connect.quint-test
  "These shell out to the real quint. They are the only tests that need it
   installed; everything downstream runs on committed traces."
  (:require [clojure.java.io :as io]
            [clojure.java.process :as process]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [uno.michelada.quint-connect.itf :as itf]
            [uno.michelada.quint-connect.quint :as quint]))

(def ^:private spec "dev/fixtures/bank.qnt")

(defn- error-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:quint/error (ex-data e)))))

(deftest ^:integration reports-a-version
  (is (re-matches #"\d+\.\d+\.\d+" (quint/version))))

(deftest ^:integration generates-traces-that-decode
  (let [{:keys [seed traces cmd]} (quint/run! {:spec spec :main "bankTest"
                                               :seed 42 :traces 2 :max-steps 4})]
    (is (= 42 seed))
    (is (= 2 (count traces)))
    (is (= ["run_0.itf.json" "run_1.itf.json"] (mapv :name traces)))
    (is (str/includes? (str/join " " cmd) "--mbt"))

    (testing "the output is real ITF that M1 decodes"
      (let [t (itf/itf->trace (itf/json->itf (:json (first traces))))]
        (is (= "bank.qnt" (:source t)))
        (is (= [:balances :lastError] (:vars t)))
        (is (= "init" (:action (first (:states t)))))))))

(defn- decoded [{:keys [traces]}]
  (mapv #(:states (itf/itf->trace (itf/json->itf (:json %)))) traces))

(deftest ^:integration seed-is-generated-and-reproducible
  (let [a (quint/run! {:spec spec :main "bankTest" :traces 2 :max-steps 3})
        b (quint/run! {:spec spec :main "bankTest" :traces 2 :max-steps 3
                       :seed (:seed a)})]
    (is (integer? (:seed a)))
    ;; Compared decoded, not as JSON: #meta.timestamp differs on every run,
    ;; which is exactly the noise M1 drops.
    (is (= (decoded a) (decoded b)) "the reported seed reproduces the run")))

(deftest ^:integration max-samples-is-a-floor-not-an-equation
  (testing "an explicit larger value is kept"
    (is (str/includes?
         (str/join " " (:cmd (quint/run! {:spec spec :main "bankTest" :seed 1
                                          :traces 2 :max-steps 3 :max-samples 7})))
         "--max-samples=7")))
  (testing ":traces raises it, because Quint rejects n-traces > max-samples"
    (let [joined (str/join " " (:cmd (quint/run! {:spec spec :main "bankTest" :seed 1
                                                  :traces 3 :max-steps 3})))]
      (is (str/includes? joined "--n-traces=3"))
      (is (str/includes? joined "--max-samples=3"))))
  (testing "more traces than samples would be rejected by quint itself"
    (is (= 3 (count (:traces (quint/run! {:spec spec :main "bankTest" :seed 1
                                          :traces 3 :max-steps 3})))))))

(deftest ^:integration temp-directories-are-cleaned
  (let [before (set (.list (io/file (System/getProperty "java.io.tmpdir"))))]
    (quint/run! {:spec spec :main "bankTest" :seed 3 :traces 1 :max-steps 3})
    (let [after (set (.list (io/file (System/getProperty "java.io.tmpdir"))))]
      (is (empty? (filter #(str/starts-with? % "quint-connect-")
                          (set/difference after before)))))))

(deftest ^:integration missing-binary-is-typed
  (let [path (System/getenv "PATH")]
    (is (= :quint-not-found
           (error-of #(with-redefs [process/start
                                    (fn [& _] (throw (java.io.IOException. "nope")))]
                        (quint/version))))
        (str "PATH was " path))))

(deftest ^:integration broken-spec-carries-quints-own-stderr
  (let [e (try (quint/run! {:spec "dev/fixtures/does-not-exist.qnt" :traces 1})
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (= :quint-failed (:quint/error (ex-data e))))
    (is (str/includes? (:stderr (ex-data e)) "does not exist")
        "verbatim, so the user reads Quint's diagnosis and not ours")
    (is (some? (:seed (ex-data e))) "the seed is reported even on failure")))

(deftest ^:integration missing-spec-is-typed
  (is (= :quint-failed (error-of #(quint/run! {:traces 1})))))

;; --- quint test: one scripted run ------------------------------------------

(def ^:private tracked-spec "dev/fixtures/tracked.qnt")

(deftest ^:integration runs-one-named-test
  (let [{:keys [traces cmd]} (quint/test! {:spec tracked-spec :main "trackedRuns"
                                           :test "depositThenOverdraftTest"})]
    (is (= 1 (count traces)))
    (is (= "test_depositThenOverdraftTest_0.itf.json" (:name (first traces))))
    (is (str/includes? (str/join " " cmd) "--match=^depositThenOverdraftTest$")
        "anchored, so one name cannot select another that starts with it")

    (testing "the trace carries no mbt:: and needs the spec's own variables"
      (let [json (:json (first traces))
            bare (itf/itf->trace (itf/json->itf json))
            path (itf/itf->trace (itf/json->itf json)
                                 {:action-path [:lastAction] :nondet-path [:lastPick]})]
        (is (every? #(nil? (:action %)) (:states bare)))
        (is (= ["init" "deposit" "withdraw" "overdraft"] (mapv :action (:states path))))))))

(deftest ^:integration a-name-that-matches-nothing-is-no-traces
  ;; Quint exits 0 and writes nothing at all, so silence has to become an error
  ;; here or the run would look like a pass.
  (is (= :no-traces
         (error-of #(quint/test! {:spec tracked-spec :main "trackedRuns"
                                  :test "noSuchTest"})))))

(deftest ^:integration a-failing-expectation-blames-the-spec
  (let [e (try (quint/test! {:spec tracked-spec :main "trackedBroken" :test "brokenTest"})
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (= :test-failed (:quint/error (ex-data e))))
    (is (str/includes? (ex-message e) "bug in the spec"))
    (is (= "brokenTest" (:test (ex-data e))))))

(deftest ^:integration test-without-a-name-is-typed
  (is (= :quint-failed (error-of #(quint/test! {:spec tracked-spec})))))
