import type { ThemePreference } from "@/app/providers/AppThemeProvider";
import {
  normalizeLocale,
  supportedLocales,
  type SupportedLocale,
} from "@lyreo/i18n";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
} from "@/components/ui/dropdown-menu";
import { RiSunLine as Sun, RiMoonLine as Moon, RiComputerLine as Monitor, RiTranslate2 as Languages } from "@remixicon/react";
import { useTranslation } from "react-i18next";
import { useLocation } from "react-router-dom";
import { SidebarTrigger, useSidebar } from "@/components/ui/sidebar";
import { Separator } from "@/components/ui/separator";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { persistAdminLocale } from "@/i18n";
import { useAppTheme } from "@/app/providers/AppThemeProvider";
import { getNavTitleKey } from "./admin-navigation";

export function AdminHeader() {
  const { open, openMobile, isMobile } = useSidebar();
  const collapsed = !open;
  const location = useLocation();
  const { t, i18n } = useTranslation(["admin", "common"]);
  const { preference, setPreference } = useAppTheme();
  const locale = normalizeLocale(i18n.language);

  return (
    <header className="admin-header">
      <div className="flex min-w-0 items-center gap-2">
        <SidebarTrigger
          aria-label={t(
            isMobile
              ? "admin:shell.openNavigation"
              : collapsed
                ? "admin:shell.expandSidebar"
                : "admin:shell.collapseSidebar",
          )}
          aria-controls="desktop-navigation"
          aria-expanded={isMobile ? openMobile : open}
        />
        <Separator orientation="vertical" className="!h-4 !self-center" />
        <span className="header-title">
          {t(getNavTitleKey(location.pathname, location.hash))}
        </span>
      </div>
      <div className="appearance-controls">
        <Select
          value={locale}
          onValueChange={(value) =>
            void persistAdminLocale(value as SupportedLocale)
          }
        >
          <SelectTrigger
            aria-label={t("admin:appearance.language")}
            className="h-8 w-[6rem] max-w-full border-transparent bg-transparent"
          >
            <Languages aria-hidden="true" />
            <SelectValue>{locale.toUpperCase()}</SelectValue>
          </SelectTrigger>
          <SelectContent>
            <SelectGroup>
              {supportedLocales.map((item) => (
                <SelectItem value={item} key={item}>
                  {t(
                    `common:language.${item === "en" ? "english" : "vietnamese"}`,
                  )}
                </SelectItem>
              ))}
            </SelectGroup>
          </SelectContent>
        </Select>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              variant="ghost"
              size="icon"
              aria-label={t("admin:appearance.theme")}
            >
              {preference === "dark" ? (
                <Moon />
              ) : preference === "light" ? (
                <Sun />
              ) : (
                <Monitor />
              )}
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            <DropdownMenuRadioGroup
              value={preference}
              onValueChange={(value) => setPreference(value as ThemePreference)}
            >
              {(["system", "light", "dark"] as const).map((item) => (
                <DropdownMenuRadioItem key={item} value={item}>
                  {t(`common:theme.${item}`)}
                </DropdownMenuRadioItem>
              ))}
            </DropdownMenuRadioGroup>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </header>
  );
}
