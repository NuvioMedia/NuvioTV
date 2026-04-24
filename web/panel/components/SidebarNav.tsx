"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  Boxes,
  Folder,
  Home,
  Library,
  Plug,
  Settings,
  Smartphone,
  Sparkles,
  Users,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";

interface NavItem {
  href: string;
  label: string;
  icon: LucideIcon;
}

export default function SidebarNav({ profileIndex }: { profileIndex: number }) {
  const pathname = usePathname();
  const base = `/p/${profileIndex}`;

  const items: NavItem[] = [
    { href: base, label: "Overview", icon: Home },
    { href: `${base}/addons`, label: "Addons", icon: Boxes },
    { href: `${base}/plugins`, label: "Plugins", icon: Plug },
    { href: `${base}/integrations`, label: "Integrations", icon: Sparkles },
    { href: `${base}/collections`, label: "Collections", icon: Folder },
    { href: `${base}/library`, label: "Library", icon: Library },
    { href: `${base}/settings`, label: "Settings", icon: Settings },
    { href: `${base}/devices`, label: "Devices", icon: Smartphone },
  ];

  return (
    <nav className="space-y-1">
      <Link
        href="/profiles"
        className="mb-4 flex items-center gap-2 rounded-lg px-3 py-2 text-sm text-slate-400 hover:bg-slate-800 hover:text-slate-100"
      >
        <Users className="h-4 w-4" />
        Switch profile
      </Link>
      {items.map((item) => {
        const Icon = item.icon;
        const active =
          item.href === base ? pathname === item.href : pathname?.startsWith(item.href);
        return (
          <Link
            key={item.href}
            href={item.href}
            className={`flex items-center gap-3 rounded-lg px-3 py-2 text-sm transition ${
              active
                ? "bg-primary/15 text-primary"
                : "text-slate-300 hover:bg-slate-800 hover:text-slate-100"
            }`}
          >
            <Icon className="h-4 w-4" />
            {item.label}
          </Link>
        );
      })}
    </nav>
  );
}
