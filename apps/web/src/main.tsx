import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "@tanstack/react-router";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { ApiError } from "./api/queries";
import { applyLook } from "./lib/theme";
import { router } from "./router";
import "./styles/tokens.css";
import "./styles/app.css";
import "./styles/screens.css";

applyLook();

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Don't hammer the API with retries for answers that won't change (404, 409…).
      retry: (count, error) =>
        count < 2 && !(error instanceof ApiError && error.status >= 400 && error.status < 500),
    },
  },
});

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);
