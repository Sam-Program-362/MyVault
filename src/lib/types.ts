export type ThemeMode = "light" | "dark" | "system";
export type FontSize = "sm" | "md" | "lg" | "xl";
export type Radius = "none" | "sm" | "md" | "lg" | "xl";

export interface AppSettings {
  id: string;
  appName: string;
  accent: string;
  mode: ThemeMode;
  fontSize: FontSize;
  radius: Radius;
  wallpaperUrl: string | null;
  wallpaperEnabled: boolean;
  wallpaperOpacity: number;
  wallpaperBlur: number;
  updatedAt: string;
}

export interface Space {
  id: string;
  name: string;
  description: string | null;
  icon: string;
  color: string;
  sortOrder: number;
  createdAt: string;
  entryCount?: number;
}

export interface Tag {
  id: string;
  name: string;
  color: string;
  textColor: string;
  reusable: boolean;
  createdAt: string;
}

export interface Entry {
  id: string;
  spaceId: string;
  title: string;
  content: string;
  coverImage: string | null;
  pinned: boolean;
  createdAt: string;
  updatedAt: string;
  tags: Tag[];
}

export interface ExportPayload {
  version: 1;
  exportedAt: string;
  settings: AppSettings;
  spaces: Space[];
  tags: Tag[];
  entries: Entry[];
}
