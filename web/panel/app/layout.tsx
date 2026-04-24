import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "NuvioTV Panel",
  description: "Control panel for your NuvioTV account",
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
