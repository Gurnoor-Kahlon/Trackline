import type { Metadata, Viewport } from "next";
import { Inter } from "next/font/google";

import { AppHeader } from "@/components/shell/AppHeader";
import { SkipLink } from "@/components/shell/SkipLink";
import { ThemeScript } from "@/components/shell/ThemeScript";

import "./globals.css";

/**
 * One typeface, loaded as a variable font and self-hosted by next/font so there
 * is no render-blocking request to a third party. Route numbers get their weight
 * and tracking from the design tokens rather than from a second family.
 */
const inter = Inter({
  subsets: ["latin"],
  display: "swap",
  variable: "--font-inter",
});

export const metadata: Metadata = {
  title: {
    default: "Trackline — See Toronto move",
    template: "%s — Trackline",
  },
  description:
    "Real-time Toronto transit intelligence: live vehicles, service alerts, vehicle spacing and route health, built on the TTC's public feeds.",
  applicationName: "Trackline",
};

export const viewport: Viewport = {
  // The map and bottom sheets need the full viewport height on mobile.
  width: "device-width",
  initialScale: 1,
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#ffffff" },
    { media: "(prefers-color-scheme: dark)", color: "#0f1318" },
  ],
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={inter.variable} suppressHydrationWarning>
      <head>
        <ThemeScript />
      </head>
      <body className="min-h-dvh antialiased">
        <SkipLink />
        <AppHeader />
        <main id="main" className="mx-auto w-full max-w-6xl px-4 py-6 sm:py-8">
          {children}
        </main>
      </body>
    </html>
  );
}
