import type { Metadata } from "next";

import { AuthForm } from "@/components/auth/AuthForm";
import { safeNext } from "@/lib/auth/safeNext";

export const metadata: Metadata = {
  title: "Sign in",
  description: "Sign in to save routes and stops.",
};

/** Rendered per request: where to go afterwards is in the address. */
export const dynamic = "force-dynamic";

export default async function LoginPage({ searchParams }: { searchParams: Promise<{ next?: string }> }) {
  const { next } = await searchParams;
  return <AuthForm mode="login" next={safeNext(next)} />;
}
