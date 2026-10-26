import { useEffect, useState } from "react";
import { ChevronRight } from "lucide-react";
import { Link, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Collapsible } from "radix-ui";
import {
  adminNavGroups,
  type AdminNavItem,
} from "@/app/layout/admin-navigation";
import {
  SidebarGroup,
  SidebarGroupLabel,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarMenuSub,
  SidebarMenuSubItem,
  SidebarMenuSubButton,
  useSidebar,
} from "@/components/ui/sidebar";
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
} from "@/components/ui/dropdown-menu";
import { Badge } from "@/components/ui/badge";
function NavItem({ item }: { item: AdminNavItem }) {
  const { t } = useTranslation("admin");
  const location = useLocation();
  const { state, isMobile, setOpenMobile } = useSidebar();
  const active = (path?: string) =>
    !!path &&
    (path.includes("#")
      ? location.pathname + location.hash === path
      : location.pathname === path);
  const childActive =
    item.children?.some((c) => active(c.path)) ||
    (item.key === "lessons" &&
      /^\/lessons\/[^/]+$/.test(location.pathname) &&
      location.pathname !== "/lessons/channels");
  const [open, setOpen] = useState(Boolean(childActive));
  useEffect(() => {
    if (childActive) setOpen(true);
  }, [location.pathname, location.hash, childActive]);
  const title = t(`navigation.${item.key}`);
  const Icon = item.icon;
  const soon = (
    <Badge variant="outline" className="ml-auto text-xs">
      {t("navigation.soon")}
    </Badge>
  );
  const close = () => setOpenMobile(false);
  if (item.children && state === "collapsed" && !isMobile)
    return (
      <SidebarMenuItem>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <SidebarMenuButton
              tooltip={title}
              aria-label={title}
              isActive={Boolean(childActive)}
            >
              <Icon />
              <span>{title}</span>
            </SidebarMenuButton>
          </DropdownMenuTrigger>
          <DropdownMenuContent side="right" align="start">
            <DropdownMenuGroup>
              {item.children.map((child) => (
                <DropdownMenuItem
                  key={child.key}
                  disabled={!child.path}
                  asChild={!!child.path}
                >
                  {child.path ? (
                    <Link to={child.path} onClick={close}>
                      {t(`navigation.${child.key}`)}
                    </Link>
                  ) : (
                    <span>
                      {t(`navigation.${child.key}`)} · {t("navigation.soon")}
                    </span>
                  )}
                </DropdownMenuItem>
              ))}
            </DropdownMenuGroup>
          </DropdownMenuContent>
        </DropdownMenu>
      </SidebarMenuItem>
    );
  if (item.children)
    return (
      <Collapsible.Root open={open} onOpenChange={setOpen} asChild>
        <SidebarMenuItem>
          <Collapsible.Trigger asChild>
            <SidebarMenuButton
              aria-label={title}
              isActive={Boolean(childActive)}
            >
              <Icon />
              <span>{title}</span>
              <ChevronRight
                className="ml-auto transition-transform"
                style={{
                  transform: open ? "rotate(90deg)" : undefined,
                }}
              />
            </SidebarMenuButton>
          </Collapsible.Trigger>
          <Collapsible.Content>
            <SidebarMenuSub>
              {item.children.map((child) => (
                <SidebarMenuSubItem key={child.key}>
                  <SidebarMenuSubButton asChild isActive={active(child.path)}>
                    {child.path ? (
                      <Link to={child.path} onClick={close}>
                        {t(`navigation.${child.key}`)}
                      </Link>
                    ) : (
                      <button
                        type="button"
                        aria-disabled="true"
                        title={t("navigation.soon")}
                      >
                        <span className="min-w-0 flex-1 truncate">
                          {t(`navigation.${child.key}`)}
                        </span>
                        {soon}
                      </button>
                    )}
                  </SidebarMenuSubButton>
                </SidebarMenuSubItem>
              ))}
            </SidebarMenuSub>
          </Collapsible.Content>
        </SidebarMenuItem>
      </Collapsible.Root>
    );
  return (
    <SidebarMenuItem>
      <SidebarMenuButton asChild tooltip={title} isActive={active(item.path)}>
        {item.path ? (
          <Link to={item.path} onClick={close} aria-label={title}>
            <Icon />
            <span>{title}</span>
          </Link>
        ) : (
          <button
            type="button"
            aria-disabled="true"
            aria-label={`${title}: ${t("navigation.soon")}`}
            title={t("navigation.soon")}
          >
            <Icon />
            <span className="min-w-0 flex-1 truncate">{title}</span>
            {soon}
          </button>
        )}
      </SidebarMenuButton>
    </SidebarMenuItem>
  );
}
export function NavMain() {
  const { t } = useTranslation("admin");
  return (
    <nav aria-label={t("shell.navigation")}>
      {adminNavGroups.map((group) => (
        <SidebarGroup key={group.key}>
          <SidebarGroupLabel>{t(`navigation.${group.key}`)}</SidebarGroupLabel>
          <SidebarMenu>
            {group.items.map((item) => (
              <NavItem key={item.key} item={item} />
            ))}
          </SidebarMenu>
        </SidebarGroup>
      ))}
    </nav>
  );
}
