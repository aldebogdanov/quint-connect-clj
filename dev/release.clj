(ns release
  "Bump the version everywhere a release touches, and say what to do next.

  Publishing stays a human step, so this stops at the edits: it never commits,
  tags, pushes or deploys. See docs/decisions/0008-release.md.

  What it exists to prevent is the two mistakes that have actually happened —
  running `bb deploy` with build.clj still on the released version, and leaving
  the tag unpushed after everything else went out."
  (:require [clojure.string :as str]))

(def ^:private coordinate-files
  "Every file that names the released coordinate. build.clj is the version's
  home; these repeat it, and the examples pin it so a copied directory runs."
  ["README.md"
   "docs/getting-started.md"
   "examples/counter/deps.edn"
   "examples/lru/deps.edn"
   "examples/queue/deps.edn"
   "examples/tokens/deps.edn"])

(defn- die [& msg]
  (binding [*out* *err*] (println (str/join " " msg)))
  (System/exit 1))

(defn- current-version []
  (second (re-find #"\(def version \"([^\"]+)\"\)" (slurp "build.clj"))))

(defn- unreleased-body
  "What stands under `## [Unreleased]`, or nil when the section is a placeholder."
  [changelog]
  (let [body (second (re-find #"(?s)## \[Unreleased\]\n\n(.*?)\n## \[" changelog))]
    (when-not (or (str/blank? body) (str/starts-with? body "Nothing since"))
      body)))

(defn- edit!
  "Apply `f` to the file, write it back, and return the number of characters
  that changed. Zero means the pattern was not found, which is worth knowing:
  a coordinate that quietly stopped matching is how a release goes out half
  bumped."
  [path f]
  (let [before (slurp path), after (f before)]
    (spit path after)
    (if (= before after) 0 1)))

(defn- coordinate-edits
  "The replacements a release makes in a file that repeats the version.

  Targeted rather than a blanket search for the old version string: the
  test-runner is pinned by a git tag that has collided with our own version
  before, and a blind replace rewrote it into a tag that does not exist."
  [from to]
  [;; org.clojars.aldebogdanov/quint-connect {:mvn/version "X"} — the artifact
   ;; and the version sit on one line in the README and on two in the tutorial
   [(re-pattern (str "(quint-connect\\s*\\{:mvn/version \")" from "(\")"))
    (str "$1" to "$2")]
   [(str "Status: **" from "**.")        (str "Status: **" to "**.")]
   [(str "**" from " is an early release.**") (str "**" to " is an early release.**")]])

(defn -main [& [version]]
  (when-not (and version (re-matches #"\d+\.\d+\.\d+" version))
    (die "usage: bb release <version>, as in bb release 0.6.0"))
  (let [from (current-version)]
    (when (= from version)
      (die "build.clj is already on" version))
    (when-not (unreleased-body (slurp "CHANGELOG.md"))
      (die "CHANGELOG.md has nothing under [Unreleased]; write it before releasing"))

    (edit! "build.clj" #(str/replace % (str "(def version \"" from "\")")
                                     (str "(def version \"" version "\")")))
    (edit! "CHANGELOG.md"
           #(str/replace-first
             % "## [Unreleased]\n\n"
             (format "## [Unreleased]\n\nNothing since %s.\n\n## [%s] — %s\n\n"
                     version version (str (java.time.LocalDate/now)))))
    (let [edits (for [f coordinate-files]
                  [f (reduce (fn [n [pat rep]] (+ n (edit! f #(str/replace % pat rep))))
                             0 (coordinate-edits from version))])]
      (doseq [[f n] edits]
        (when (zero? n)
          (die "no" from "coordinate found in" f "- the release would go out half bumped")))
      (println (format "%s -> %s in build.clj, CHANGELOG.md and %d coordinate files.\n"
                       from version (count edits))))
    (println "Read the diff, then:\n")
    (println "  bb test && bb test:all && bb install")
    (println (format "  git commit -am 'release: %s' && git tag -a v%s -m 'v%s'" version version version))
    (println (format "  git push origin main && git push origin v%s" version))
    (println "  bb deploy      # reads CLOJARS_USERNAME and a deploy token in CLOJARS_PASSWORD")))
