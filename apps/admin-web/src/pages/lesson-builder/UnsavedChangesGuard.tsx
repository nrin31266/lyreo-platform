import { useContext, useEffect } from "react";
import { UNSAFE_DataRouterContext, useBlocker, type BlockerFunction } from "react-router-dom";
import { useTranslation } from "react-i18next";
type SearchGuard = boolean | BlockerFunction;

function RouterGuard({ dirty, includeSearch }: { dirty: boolean; includeSearch: SearchGuard }) {
  const { t } = useTranslation("admin");
  const blocker = useBlocker(navigation => {
    const { currentLocation, nextLocation } = navigation;
    if (!dirty) return false;
    if (currentLocation.pathname !== nextLocation.pathname) return true;
    if (currentLocation.search === nextLocation.search) return false;
    return typeof includeSearch === "function" ? includeSearch(navigation) : includeSearch;
  });
  useEffect(() => {
    if (blocker.state === "blocked") {
      if (window.confirm(t("catalog.unsaved"))) blocker.proceed();
      else blocker.reset();
    }
  }, [blocker, t]);
  return null;
}
export function UnsavedChangesGuard({ dirty, includeSearch = true }: { dirty: boolean; includeSearch?: SearchGuard }) {
  const router = useContext(UNSAFE_DataRouterContext);
  useEffect(() => {
    if (!dirty) return;
    const unload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", unload);
    return () => window.removeEventListener("beforeunload", unload);
  }, [dirty]);
  return router ? <RouterGuard dirty={dirty} includeSearch={includeSearch} /> : null;
}
