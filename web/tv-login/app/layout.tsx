import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Omnio TV Login",
  description: "Sign in to your Omnio TV account",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body className="min-h-screen bg-slate-900 text-slate-50 antialiased">
        {children}
      </body>
    </html>
  );
}
