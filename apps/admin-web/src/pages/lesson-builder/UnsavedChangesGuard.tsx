import { useContext, useEffect } from "react";
import { UNSAFE_DataRouterContext, useBlocker } from "react-router-dom";
import { useTranslation } from "react-i18next";
function RouterGuard({ dirty, includeSearch }: { dirty: boolean; includeSearch: boolean }) {
  const { t } = useTranslation("admin");
  const blocker = useBlocker(({ currentLocation, nextLocation }) => dirty && (currentLocation.pathname !== nextLocation.pathname || includeSearch && currentLocation.search !== nextLocation.search));
  useEffect(() => {
    if (blocker.state === "blocked") {
      if (window.confirm(t("catalog.unsaved"))) blocker.proceed();
      else blocker.reset();
    }
  }, [blocker, t]);
  return null;
}
export function UnsavedChangesGuard({ dirty, includeSearch = true }: { dirty: boolean; includeSearch?: boolean }) {
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
