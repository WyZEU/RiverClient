import { cs } from "./cs.js";

const DICTIONARIES = { cs };

/** Looks up `text` (the English source string) in `language`'s dictionary.
 *  Untranslated languages, and untranslated strings within a real dictionary,
 *  both just return the English text back - so a half-finished translation
 *  still renders instead of showing a missing key. */
export function translate(language, text, vars) {
  const dictionary = DICTIONARIES[language];
  const template = dictionary?.[text] ?? text;
  // {name}-style placeholders: the English source string carries them too, so a
  // translation only needs to move the placeholder, never invent a new syntax for it.
  if (!vars) return template;
  return template.replace(/\{(\w+)\}/g, (match, key) => (key in vars ? String(vars[key]) : match));
}

export const SUPPORTED_LANGUAGES = [
  { value: "en", label: "English" },
  { value: "cs", label: "Čeština" }
];
