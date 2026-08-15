(ns uno.michelada.quint-connect.quint-test
  "These shell out to the real quint. They are the only tests that need it
   installed; everything downstream runs on committed traces."
  (:require [clojure.string :as str]
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
  (let [before (set (.list (clojure.java.io/file (System/getProperty "java.io.tmpdir"))))]
    (quint/run! {:spec spec :main "bankTest" :seed 3 :traces 1 :max-steps 3})
    (let [after (set (.list (clojure.java.io/file (System/getProperty "java.io.tmpdir"))))]
      (is (empty? (filter #(str/starts-with? % "quint-connect-")
                          (clojure.set/difference after before)))))))

(deftest ^:integration missing-binary-is-typed
  (let [path (System/getenv "PATH")]
    (is (= :quint-not-found
           (error-of #(with-redefs [clojure.java.process/start
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
