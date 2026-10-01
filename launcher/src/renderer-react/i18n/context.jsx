import React, { createContext, useContext, useMemo } from "react";
import { translate } from "./translate.js";

/*
  English never needs a dictionary of its own: it is the literal text already
  sitting in every t("...") call, so it doubles as the fallback whenever a
  translation is missing or a language has no entry for a given string yet.
*/
const LanguageContext = createContext("en");

export function I18nProvider({ language, children }) {
  return <LanguageContext.Provider value={language || "en"}>{children}</LanguageContext.Provider>;
}

/** `const t = useT();` then `t("Sign in")` - falls back to the English text
 *  passed in whenever the current language has no translation for it. */
export function useT() {
  const language = useContext(LanguageContext);
  return useMemo(() => (text, vars) => translate(language, text, vars), [language]);
}
