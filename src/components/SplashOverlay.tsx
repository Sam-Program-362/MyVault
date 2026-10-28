"use client";

import { useMemo, useState } from "react";
import { Plus, Sparkles } from "lucide-react";
import { useAppData } from "@/context/AppDataContext";
import Wallpaper from "@/components/Wallpaper";

const ICON_CHOICES = ["📁", "📝", "💡", "🧠", "🎨", "🛠️", "📚", "🎮", "🍳", "💼", "🌱", "🔒"];
const COLOR_CHOICES = [
  "#6366f1",
  "#ec4899",
  "#f59e0b",
  "#10b981",
  "#06b6d4",
  "#8b5cf6",
  "#ef4444",
  "#64748b",
];

function AnimatedTitle({ text }: { text: string }) {
  const letters = useMemo(() => text.split(""), [text]);
  return (
    <h1 className="text-[clamp(2.2rem,8vw,4.2rem)] font-black tracking-tight text-white drop-shadow-[0_6px_30px_rgba(0,0,0,0.45)]">
      {letters.map((letter, i) => (
        <span
          key={`${letter}-${i}`}
          className="splash-letter"
          style={{ animationDelay: `${i * 0.045}s` }}
        >
          {letter === " " ? "\u00A0" : letter}
        </span>
      ))}
    </h1>
  );
}

export default function SplashOverlay() {
  const { settings, spaces, addSpace, setEntered, setCurrentSpaceId } = useAppData();
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
  const [icon, setIcon] = useState(ICON_CHOICES[0]);
  const [color, setColor] = useState(COLOR_CHOICES[0]);
  const [busy, setBusy] = useState(false);

  if (!settings) return null;

  const enter = (spaceId: string) => {
    setCurrentSpaceId(spaceId);
    setEntered(true);
  };

  const handleCreate = async () => {
    if (!name.trim() || busy) return;
    setBusy(true);
    try {
      const space = await addSpace({ name: name.trim(), icon, color });
      setName("");
      setCreating(false);
      enter(space.id);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex flex-col items-center overflow-y-auto bg-neutral-950">
      <Wallpaper splash />
      <div className="relative z-10 flex w-full flex-1 flex-col items-center px-6 py-14">
        <div className="flex flex-1 flex-col items-center justify-center text-center">
          <div className="fade-up mb-3 flex items-center gap-2 rounded-full border border-white/15 bg-white/10 px-4 py-1.5 text-xs uppercase tracking-[0.25em] text-white/70 backdrop-blur">
            <Sparkles size={14} className="glow-pulse" style={{ color: "var(--accent)" }} />
            Your organized vault
          </div>
          <AnimatedTitle text={settings.appName || "MyVault"} />
          <p className="fade-up mt-4 max-w-md text-balance text-sm text-white/60" style={{ animationDelay: "0.3s" }}>
            Pick a space to jump into, or create a new one. Everything you write stays neatly
            organized, searchable, and yours.
          </p>
        </div>

        <div className="w-full max-w-3xl pb-10">
          <div className="fade-up grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4" style={{ animationDelay: "0.4s" }}>
            {spaces.map((space, i) => (
              <button
                key={space.id}
                onClick={() => enter(space.id)}
                className="pop-in group flex flex-col items-start gap-2 rounded-2xl border border-white/10 bg-white/[0.06] p-4 text-left backdrop-blur transition hover:-translate-y-1 hover:border-white/30 hover:bg-white/10"
                style={{ animationDelay: `${0.45 + i * 0.05}s` }}
              >
                <span
                  className="flex h-10 w-10 items-center justify-center rounded-xl text-xl"
                  style={{ background: `${space.color}33` }}
                >
                  {space.icon}
                </span>
                <span className="font-semibold text-white">{space.name}</span>
                <span className="text-xs text-white/50">{space.entryCount ?? 0} entries</span>
              </button>
            ))}

            {!creating && (
              <button
                onClick={() => setCreating(true)}
                className="pop-in flex min-h-[112px] flex-col items-center justify-center gap-2 rounded-2xl border border-dashed border-white/25 text-white/60 transition hover:border-white/50 hover:text-white"
                style={{ animationDelay: `${0.45 + spaces.length * 0.05}s` }}
              >
                <Plus size={20} />
                <span className="text-sm">New space</span>
              </button>
            )}
          </div>

          {creating && (
            <div className="pop-in mt-4 rounded-2xl border border-white/15 bg-white/10 p-4 backdrop-blur">
              <input
                autoFocus
                value={name}
                onChange={(e) => setName(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && handleCreate()}
                placeholder="Space name, e.g. Work notes"
                className="w-full rounded-xl border border-white/20 bg-white/10 px-4 py-2.5 text-white placeholder:text-white/40 outline-none focus:border-white/50"
              />
              <div className="mt-3 flex flex-wrap items-center gap-2">
                {ICON_CHOICES.map((ic) => (
                  <button
                    key={ic}
                    onClick={() => setIcon(ic)}
                    className={`flex h-8 w-8 items-center justify-center rounded-lg text-base ${icon === ic ? "ring-2 ring-white" : "bg-white/10"}`}
                  >
                    {ic}
                  </button>
                ))}
              </div>
              <div className="mt-3 flex flex-wrap items-center gap-2">
                {COLOR_CHOICES.map((c) => (
                  <button
                    key={c}
                    onClick={() => setColor(c)}
                    className={`h-6 w-6 rounded-full ${color === c ? "ring-2 ring-white ring-offset-2 ring-offset-neutral-950" : ""}`}
                    style={{ background: c }}
                  />
                ))}
              </div>
              <div className="mt-4 flex justify-end gap-2">
                <button
                  onClick={() => setCreating(false)}
                  className="rounded-xl px-3 py-2 text-sm text-white/60 hover:text-white"
                >
                  Cancel
                </button>
                <button
                  onClick={handleCreate}
                  disabled={!name.trim() || busy}
                  className="accent-bg rounded-xl px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
                >
                  {busy ? "Creating…" : "Create & enter"}
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
