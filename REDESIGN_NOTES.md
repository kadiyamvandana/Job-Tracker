# Job Tracker Professional Redesign

Updated frontend presentation files:
- `job-tracker-frontend/src/professional.css` — responsive professional design layer.
- `job-tracker-frontend/src/main.tsx` — imports the new stylesheet after index.css.
- `job-tracker-frontend/src/components/Layout.tsx` — separates public pages from the authenticated workspace shell.
- `job-tracker-frontend/src/components/Navbar.tsx` — updated brand and navigation.
- `job-tracker-frontend/src/components/Sidebar.tsx` — refreshed workspace navigation.
- `job-tracker-frontend/src/pages/Dashboard.tsx` — redesigned dashboard using the existing statistics API.
- `job-tracker-frontend/src/pages/Home.tsx` — redesigned public landing page.

## Run locally
1. Extract this ZIP.
2. Open a terminal in `online_job_tracker/job-tracker-frontend`.
3. Run `npm install`.
4. Run `npm run dev`.

The ZIP excludes generated `node_modules`, backend `target`, and frontend `dist` directories. Your package manifests and source files are included. Existing backend/API service files were not intentionally changed.
