import { createFileRoute, redirect } from "@tanstack/react-router";

export const Route = createFileRoute("/p/$profileId/settings/")({
  beforeLoad: ({ params }) => {
    throw redirect({
      to: "/p/$profileId/settings/trakt",
      params: { profileId: params.profileId },
    });
  },
});
