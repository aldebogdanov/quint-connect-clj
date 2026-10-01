(ns org.clojars.aldebogdanov.quint-connect.failure-artifact-test
  "What happens to a failure after it is found: it becomes a file. Producing
  one takes Quint and lives in end-to-end-test; here the result map is built by
  hand, around a real recorded trace."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [org.clojars.aldebogdanov.quint-connect.report :as report]
            [org.clojars.aldebogdanov.quint-connect.test :as qt])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir []
  (str (Files/createTempDirectory "quint-connect-" (into-array FileAttribute []))))

(def ^:private recorded (slurp "dev/fixtures/bank_run_0.itf.json"))

(def ^:private diverged
  {:ok?     false
   :seed    42
   :traces  5
   :cmd     ["quint" "run" "bank.qnt" "--mbt" "--seed=42"]
   :failure {:trace 3 :trace-name "run_3.itf.json" :trace-json recorded
             :step 2 :action "deposit"}})

(deftest saves-quints-own-bytes-under-a-name-that-identifies-the-run
  (let [dir   (temp-dir)
        saved (qt/save-failure! diverged {:dir dir})
        path  (get-in saved [:failure :saved])]
    (is (= (str (io/file dir "bank-seed42-trace3.itf.json")) path)
        "the spec, the seed and the trace index are all in the name")
    (is (= recorded (slurp path)) "written verbatim, not re-encoded")
    (is (= (dissoc (:failure diverged) :saved)
           (dissoc (:failure saved) :saved))
        "and nothing else about the result changes")))

(deftest the-name-is-deterministic-so-a-rerun-rewrites-one-file
  (let [dir (temp-dir)]
    (qt/save-failure! diverged {:dir dir})
    (qt/save-failure! diverged {:dir dir})
    (is (= 1 (count (.listFiles (io/file dir))))
        "same seed and index is the same trace; near-copies would pile up")))

(deftest the-name-can-be-given
  (let [dir   (temp-dir)
        saved (qt/save-failure! diverged {:dir dir :name "overdraft.itf.json"})]
    (is (= (str (io/file dir "overdraft.itf.json"))
           (get-in saved [:failure :saved])))))

(deftest a-name-with-a-path-in-it-is-refused
  ;; :dir chooses where; :name chooses what it is called. A separator in the
  ;; name would write outside the directory the caller named, which is not a
  ;; thing to find out from a file in an unexpected place.
  (let [e (try (qt/save-failure! diverged {:name "../escaped.itf.json"})
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (= :save-failed (:quint/error (ex-data e))))
    (is (str/includes? (ex-message e) ":dir") "and say which option does mean that")))

(deftest a-passing-result-writes-nothing
  (let [dir    (str (io/file (temp-dir) "failures"))
        result {:ok? true :seed 42 :traces 5 :failure nil}]
    (is (= result (qt/save-failure! result {:dir dir})))
    (is (not (.exists (io/file dir))) "not even the directory")))

(deftest an-unwritable-directory-is-a-typed-error
  (let [blocked (io/file (temp-dir) "not-a-directory")]
    (spit blocked "")
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (qt/save-failure! diverged {:dir (str blocked)})))]
      (is (= :save-failed (:quint/error (ex-data e))))
      (is (str/includes? (ex-message e) ":save-failure false")
          "the message says how to turn the writing off")
      (is (instance? java.io.IOException (:cause (ex-data e)))))))

(deftest the-failure-message-names-the-file-it-just-wrote
  (let [saved (qt/save-failure! diverged {:dir (temp-dir)})
        s     (report/result-str saved)]
    (is (str/includes? s (get-in saved [:failure :saved])))
    (is (str/includes? s "saved"))))

(deftest a-spec-that-cannot-be-named-still-gets-a-file
  (let [dir   (temp-dir)
        saved (qt/save-failure! (dissoc diverged :cmd) {:dir dir})]
    (is (= (str (io/file dir "trace-seed42-trace3.itf.json"))
           (get-in saved [:failure :saved])))))

(deftest a-spec-given-by-path-does-not-nest-the-artifact
  (let [dir   (temp-dir)
        saved (qt/save-failure! (assoc diverged :cmd ["quint" "run" "spec/bank.qnt"])
                                {:dir dir})]
    (is (= (str (io/file dir "bank-seed42-trace3.itf.json"))
           (get-in saved [:failure :saved]))
        "the directory the spec lives in is not part of the name")))

;; --- a counterexample is an artifact too -----------------------------------

(def ^:private counterexample (slurp "dev/fixtures/tracked_verify_underFifty.itf.json"))

(def ^:private violated
  "What `verify` returns when the invariant fails and the implementation
  reproduces it: no :failure at all, and the trace hanging off :invariant."
  {:ok?       false
   :seed      nil
   :traces    1
   :cmd       ["quint" "verify" "/somewhere/tracked.qnt" "--invariant=underFifty"]
   :invariant {:name "underFifty" :holds? false
               :trace-name "verify.itf.json" :trace-json counterexample}
   :failure   nil})

(deftest a-counterexample-is-saved-even-though-nothing-diverged
  (let [dir   (temp-dir)
        saved (qt/save-failure! violated {:dir dir})
        path  (get-in saved [:invariant :saved])]
    (is (= (str (io/file dir "tracked-underFifty-counterexample.itf.json")) path)
        "named after the invariant: Apalache rolled no dice, so there is no seed")
    (is (= counterexample (slurp path)))
    (is (nil? (get-in saved [:failure :saved])) "there was no failure to annotate")
    (is (str/includes? (report/result-str saved) path)
        "and the message says where it landed")))

(deftest a-verify-result-that-also-diverged-still-saves-one-file
  ;; Both keys carry the same trace in that case; writing it twice under two
  ;; names would be two copies of one counterexample.
  (let [dir   (temp-dir)
        both  (assoc violated :failure {:trace 0 :trace-name "verify.itf.json"
                                        :trace-json counterexample
                                        :step 1 :action "deposit"})
        saved (qt/save-failure! both {:dir dir})]
    (is (= 1 (count (.listFiles (io/file dir)))))
    (is (= (str (io/file dir "tracked-underFifty-counterexample.itf.json"))
           (get-in saved [:invariant :saved])))))

;; --- a scripted run is identified by its name -------------------------------

(def ^:private run-recorded
  (slurp "dev/fixtures/choreo/tpc_tracked_test_commitTest.itf.json"))

(def ^:private scripted
  {:ok?     false
   :seed    nil
   :test    "commitTest"
   :traces  1
   :cmd     ["quint" "test" "two_phase_commit.qnt" "--out-itf=test_{test}_{seq}.itf.json"
             "--verbosity=0" "--match=^commitTest$"]
   :failure {:trace 0 :trace-name "test_commitTest_0.itf.json" :trace-json run-recorded
             :step 4 :action "DecidesOnCommit"}})

(deftest a-scripted-run-is-saved-under-its-name-not-a-seed
  ;; It used to be two_phase_commit-seed-trace0.itf.json: no seed was passed,
  ;; and the name was built for random runs, where the seed is the identity.
  (let [dir  (temp-dir)
        path (get-in (qt/save-failure! scripted {:dir dir}) [:failure :saved])]
    (is (= (str (io/file dir "two_phase_commit-commitTest.itf.json")) path))
    (is (= run-recorded (slurp path)))))

(deftest a-scripted-run-is-reported-by-its-name
  (let [s (report/result-str scripted)]
    (is (str/includes? s "diverged on run \"commitTest\""))
    (is (not (str/includes? s "seed")) "there is no seed to name")
    (is (str/includes? (report/result-str (assoc scripted :seed 7)) "run \"commitTest\", seed 7")
        "unless one was passed")))
