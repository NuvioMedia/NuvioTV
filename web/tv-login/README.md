# Omnio TV Login Web App

A minimal Next.js web application that handles the TV login and account-creation flow for Omnio TV.

## Overview

This app serves the `app.omnio.tv/tv-login` route. It allows users to:
1. Scan a QR code on their TV which directs them to this app with a `?code=ABC123` parameter.
2. Sign in with an existing Supabase account or create a new one using email and password.
3. Approve the pending TV login session by calling the `approve_tv_login_session` Supabase RPC.
4. Return from an email-confirmation link to the same TV approval URL when Supabase requires sign-up verification.

## Tech Stack

- Next.js 14 (App Router)
- React 18
- Tailwind CSS
- Supabase JS Client
- Lucide React (Icons)

## Local Development

1. Install dependencies:
   ```bash
   npm install
   ```

2. Create a `.env.local` file in the root of this directory with your Supabase credentials:
   ```env
   NEXT_PUBLIC_SUPABASE_URL=your_supabase_project_url
   NEXT_PUBLIC_SUPABASE_ANON_KEY=your_supabase_anon_key
   ```

3. Run the development server:
   ```bash
   npm run dev
   ```

4. Open [http://localhost:3000/tv-login?code=TEST_CODE](http://localhost:3000/tv-login?code=TEST_CODE) in your browser.

## Vercel Deployment

This app is designed to be deployed on Vercel (Hobby tier is sufficient).

1. Push this code to a GitHub repository.
2. Import the project in Vercel.
3. Set the Root Directory to `web/tv-login`.
4. Add the following Environment Variables in the Vercel dashboard:
   - `NEXT_PUBLIC_SUPABASE_URL`
   - `NEXT_PUBLIC_SUPABASE_ANON_KEY`
5. Deploy!
6. (Optional) Configure your custom domain (e.g., `app.omnio.tv`).
