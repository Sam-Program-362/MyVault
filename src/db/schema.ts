import {
  boolean,
  integer,
  pgTable,
  primaryKey,
  text,
  timestamp,
  uuid,
} from "drizzle-orm/pg-core";

// Singleton application settings (theme, wallpaper, app name, etc).
export const settings = pgTable("settings", {
  id: text("id").primaryKey().default("singleton"),
  appName: text("app_name").notNull().default("MyVault"),
  accent: text("accent").notNull().default("#6366f1"),
  mode: text("mode").notNull().default("system"), // light | dark | system
  fontSize: text("font_size").notNull().default("md"), // sm | md | lg | xl
  radius: text("radius").notNull().default("lg"), // sm | md | lg | xl | none
  wallpaperUrl: text("wallpaper_url"),
  wallpaperEnabled: boolean("wallpaper_enabled").notNull().default(false),
  wallpaperOpacity: integer("wallpaper_opacity").notNull().default(35),
  wallpaperBlur: integer("wallpaper_blur").notNull().default(0),
  updatedAt: timestamp("updated_at").notNull().defaultNow(),
});

// Spaces / tabs that group entries together (e.g. "Work", "Recipes").
export const spaces = pgTable("spaces", {
  id: uuid("id").primaryKey(),
  name: text("name").notNull(),
  description: text("description"),
  icon: text("icon").notNull().default("📁"),
  color: text("color").notNull().default("#6366f1"),
  sortOrder: integer("sort_order").notNull().default(0),
  createdAt: timestamp("created_at").notNull().defaultNow(),
});

// Reusable tags, each with its own saved color/style.
export const tags = pgTable("tags", {
  id: uuid("id").primaryKey(),
  name: text("name").notNull().unique(),
  color: text("color").notNull().default("#6366f1"),
  textColor: text("text_color").notNull().default("#ffffff"),
  // Whether this tag should show up as a reusable suggestion for future
  // entries, or was only meant for this one-off use.
  reusable: boolean("reusable").notNull().default(true),
  createdAt: timestamp("created_at").notNull().defaultNow(),
});

// Entries hold the actual title/manual-style name, markdown content and an
// optional cover image.
export const entries = pgTable("entries", {
  id: uuid("id").primaryKey(),
  spaceId: uuid("space_id")
    .notNull()
    .references(() => spaces.id, { onDelete: "cascade" }),
  title: text("title").notNull().default("Untitled entry"),
  content: text("content").notNull().default(""),
  coverImage: text("cover_image"),
  pinned: boolean("pinned").notNull().default(false),
  createdAt: timestamp("created_at").notNull().defaultNow(),
  updatedAt: timestamp("updated_at").notNull().defaultNow(),
});

// Many-to-many join table between entries and tags.
export const entryTags = pgTable(
  "entry_tags",
  {
    entryId: uuid("entry_id")
      .notNull()
      .references(() => entries.id, { onDelete: "cascade" }),
    tagId: uuid("tag_id")
      .notNull()
      .references(() => tags.id, { onDelete: "cascade" }),
  },
  (table) => [primaryKey({ columns: [table.entryId, table.tagId] })],
);
