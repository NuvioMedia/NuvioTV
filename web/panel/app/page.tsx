import { redirect } from "next/navigation";

export default function Home() {
  // Middleware ensures we only reach here when authenticated.
  redirect("/profiles");
}
