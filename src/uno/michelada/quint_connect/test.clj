(ns uno.michelada.quint-connect.test
  "clojure.test integration: one assertion carrying the whole failure, and the
  failing trace written where a regression test can replay it."
  (:require [clojure.java.io :as io]
            [clojure.test :as t]
            [uno.michelada.quint-connect.core :as core]
            [uno.michelada.quint-connect.report :as report]))

(def default-failure-dir
  "Where a failing trace lands unless `:save-failure` names somewhere else. A
  drop zone, not an archive: gitignore it, and promote the traces worth keeping
  into `test-resources/quint-connect/` by hand, so a failing run never dirties
  the repository on its own."
  "test-resources/quint-connect/failures")

(defn- fail [error msg data]
  (throw (ex-info msg (assoc data :quint/error error))))

(defn- spec-name
  "The spec's basename, read out of the reproduce line. Two drivers in one
  repository must not overwrite each other's artifact."
  [cmd]
  (or (some #(second (re-matches #"(?:.*/)?([^/]+)\.qnt" %)) cmd) "trace"))

(defn- artifact-name [{:keys [seed cmd failure]}]
  (str (spec-name cmd) "-seed" seed "-trace" (:trace failure) ".itf.json"))

(defn save-failure!
  "Write the failing trace of a `core/check` result to disk, and return the
  result with the path added at `[:failure :saved]`. This is what gives the
  trace a permanent home: the temporary directory Quint wrote it to is deleted
  before `check` returns.

  Takes the result and an options map:

    :dir   directory to write into, default `default-failure-dir`
    :name  file name, default `<spec>-seed<seed>-trace<i>.itf.json`

  The default name is deterministic on purpose — the same seed and trace index
  is the same spec trace, so re-running a failure rewrites one file instead of
  piling up near-copies. Quint's own bytes are written verbatim. An `:ok?`
  result is returned unchanged and nothing is written.

  Throws `ex-info` with `:quint/error` `:save-failed` if the file cannot be
  written: an unwritable directory is a mistake to fix, not something to
  discover as an `IOException` in place of the divergence that caused it."
  ([result] (save-failure! result nil))
  ([{:keys [failure] :as result} {:keys [dir] fname :name}]
   (if-not (:trace-json failure)
     result
     (let [f (io/file (or dir default-failure-dir) (or fname (artifact-name result)))]
       (try
         (io/make-parents f)
         (spit f (:trace-json failure))
         (catch java.io.IOException e
           (fail :save-failed
                 (str "could not write the failing trace to " f
                      "; fix the directory or pass :save-failure false")
                 {:path (str f) :cause e})))
       (assoc-in result [:failure :saved] (str f))))))

(defn check
  "Run `core/check` and assert the result, so a divergence fails the enclosing
  `deftest` with the step, action, handler var, diff and reproduce line as its
  message. Returns the result map, so a test may assert further on it.

  A divergence also saves the failing trace with `save-failure!`, since that
  file is what turns a random failure into a committed regression test.
  `:save-failure`, in `opts` or in the driver, controls it: `true` (the
  default) writes to `default-failure-dir`, a string writes to that directory,
  `false` writes nothing."
  [driver opts]
  (let [save (get opts :save-failure (get driver :save-failure true))
        r    (cond-> (core/check driver opts)
               save (save-failure! (when (string? save) {:dir save})))]
    (t/is (:ok? r) (report/result-str r))
    r))

(defn replay-file
  "Run `core/replay-file` and assert the result, so a trace committed by
  `save-failure!` is a one-line regression test. Needs no Quint. Returns the
  result map."
  [driver path]
  (let [r (core/replay-file driver path)]
    (t/is (:ok? r) (str "committed trace " path " diverged\n" (report/failure-str r)))
    r))
