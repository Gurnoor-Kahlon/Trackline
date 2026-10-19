import type { Metadata } from "next";

import { AuthForm } from "@/components/auth/AuthForm";
import { safeNext } from "@/lib/auth/safeNext";

export const metadata: Metadata = {
  title: "Create an account",
  description: "Create an account to save routes and stops.",
};

/** Rendered per request: where to go afterwards is in the address. */
export const dynamic = "force-dynamic";

export default async function RegisterPage({ searchParams }: { searchParams: Promise<{ next?: string }> }) {
  const { next } = await searchParams;
  return <AuthForm mode="register" next={safeNext(next)} />;
}
