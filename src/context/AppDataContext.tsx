"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { api } from "@/lib/api";
import type { AppSettings, Space, Tag } from "@/lib/types";

const FONT_SCALES: Record<AppSettings["fontSize"], number> = {
  sm: 0.9,
  md: 1,
  lg: 1.12,
  xl: 1.26,
};

const RADIUS_SCALES: Record<AppSettings["radius"], string> = {
  none: "0px",
  sm: "0.4rem",
  md: "0.75rem",
  lg: "1.1rem",
  xl: "1.6rem",
};

function applyTheme(settings: AppSettings) {
  if (typeof document === "undefined") return;
  const root = document.documentElement;
  root.style.setProperty("--accent", settings.accent);
  root.style.setProperty("--font-scale", String(FONT_SCALES[settings.fontSize] ?? 1));
  root.style.setProperty("--radius-ui", RADIUS_SCALES[settings.radius] ?? "1.1rem");

  const prefersDark = window.matchMedia?.("(prefers-color-scheme: dark)").matches;
  const isDark = settings.mode === "dark" || (settings.mode === "system" && prefersDark);
  root.classList.toggle("dark", isDark);
  document.title = settings.appName || "MyVault";
}

interface AppDataContextValue {
  loading: boolean;
  settings: AppSettings | null;
  spaces: Space[];
  tags: Tag[];
  entered: boolean;
  currentSpaceId: string | null;
  setEntered: (value: boolean) => void;
  setCurrentSpaceId: (id: string | null) => void;
  updateSettings: (patch: Partial<AppSettings>) => Promise<void>;
  refreshSpaces: () => Promise<void>;
  refreshTags: () => Promise<void>;
  addSpace: (data: Partial<Space>) => Promise<Space>;
  renameSpace: (id: string, patch: Partial<Space>) => Promise<void>;
  deleteSpace: (id: string) => Promise<void>;
}

const AppDataContext = createContext<AppDataContextValue | null>(null);

export function AppDataProvider({ children }: { children: ReactNode }) {
  const [loading, setLoading] = useState(true);
  const [settings, setSettings] = useState<AppSettings | null>(null);
  const [spaces, setSpaces] = useState<Space[]>([]);
  const [tags, setTags] = useState<Tag[]>([]);
  const [entered, setEntered] = useState(false);
  const [currentSpaceId, setCurrentSpaceId] = useState<string | null>(null);

  const refreshSpaces = useCallback(async () => {
    const data = await api.spaces.list();
    setSpaces(data);
  }, []);

  const refreshTags = useCallback(async () => {
    const data = await api.tags.list();
    setTags(data);
  }, []);

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const [settingsData, spacesData, tagsData] = await Promise.all([
          api.settings.get(),
          api.spaces.list(),
          api.tags.list(),
        ]);
        if (!active) return;
        setSettings(settingsData);
        setSpaces(spacesData);
        setTags(tagsData);
        applyTheme(settingsData);
        if (spacesData.length > 0) setCurrentSpaceId(spacesData[0].id);
      } finally {
        if (active) setLoading(false);
      }
    })();
    return () => {
      active = false;
    };
  }, []);

  const updateSettings = useCallback(async (patch: Partial<AppSettings>) => {
    setSettings((prev) => {
      const next = prev ? { ...prev, ...patch } : prev;
      if (next) applyTheme(next);
      return next;
    });
    const updated = await api.settings.update(patch);
    setSettings(updated);
    applyTheme(updated);
  }, []);

  const addSpace = useCallback(
    async (data: Partial<Space>) => {
      const created = await api.spaces.create(data);
      setSpaces((prev) => [...prev, created]);
      return created;
    },
    [],
  );

  const renameSpace = useCallback(async (id: string, patch: Partial<Space>) => {
    const updated = await api.spaces.update(id, patch);
    setSpaces((prev) => prev.map((s) => (s.id === id ? { ...s, ...updated } : s)));
  }, []);

  const deleteSpace = useCallback(
    async (id: string) => {
      await api.spaces.remove(id);
      setSpaces((prev) => prev.filter((s) => s.id !== id));
      setCurrentSpaceId((prev) => (prev === id ? null : prev));
    },
    [],
  );

  const value = useMemo<AppDataContextValue>(
    () => ({
      loading,
      settings,
      spaces,
      tags,
      entered,
      currentSpaceId,
      setEntered,
      setCurrentSpaceId,
      updateSettings,
      refreshSpaces,
      refreshTags,
      addSpace,
      renameSpace,
      deleteSpace,
    }),
    [
      loading,
      settings,
      spaces,
      tags,
      entered,
      currentSpaceId,
      updateSettings,
      refreshSpaces,
      refreshTags,
      addSpace,
      renameSpace,
      deleteSpace,
    ],
  );

  return <AppDataContext.Provider value={value}>{children}</AppDataContext.Provider>;
}

export function useAppData() {
  const ctx = useContext(AppDataContext);
  if (!ctx) throw new Error("useAppData must be used within AppDataProvider");
  return ctx;
}
