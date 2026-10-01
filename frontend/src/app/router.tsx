import { createBrowserRouter } from 'react-router'
import { AuditPage } from '@/features/admin/pages/AuditPage'
import { UsersAdminPage } from '@/features/admin/pages/UsersAdminPage'
import { LoginPage } from '@/features/auth/pages/LoginPage'
import { RegisterPage } from '@/features/auth/pages/RegisterPage'
import { LineupPage } from '@/features/competition/pages/LineupPage'
import { MatchDetailPage } from '@/features/competition/pages/MatchDetailPage'
import { RefereeMatchesPage } from '@/features/competition/pages/RefereeMatchesPage'
import { HomePage } from '@/features/home/pages/HomePage'
import { FreeAgentsPage } from '@/features/players/pages/FreeAgentsPage'
import { MyJoinRequestsPage } from '@/features/players/pages/MyJoinRequestsPage'
import { ProfilePage } from '@/features/players/pages/ProfilePage'
import { MyTeamPage } from '@/features/teams/pages/MyTeamPage'
import { TeamDetailPage } from '@/features/teams/pages/TeamDetailPage'
import { TeamsPage } from '@/features/teams/pages/TeamsPage'
import { TournamentDetailPage } from '@/features/tournaments/pages/TournamentDetailPage'
import { TournamentsPage } from '@/features/tournaments/pages/TournamentsPage'
import { AppLayout } from './AppLayout'
import { RequireAuth, RequireRole } from './guards'
import { NotFoundPage } from './placeholders'
import { RouteErrorPage } from './RouteErrorPage'

export const router = createBrowserRouter([
  {
    // Root route: catches loader/render errors of every page (including a thrown 404 response).
    errorElement: <RouteErrorPage />,
    children: [
      { path: '/login', element: <LoginPage /> },
      { path: '/register', element: <RegisterPage /> },
      {
        element: <RequireAuth />,
        children: [
          {
            element: <AppLayout />,
            children: [
              { index: true, element: <HomePage /> },
              { path: 'profile', element: <ProfilePage /> },
              { path: 'my-requests', element: <RequireRole roles={['PLAYER']} exact><MyJoinRequestsPage /></RequireRole> },
              { path: 'players', element: <RequireRole roles={['CAPTAIN', 'ORGANIZER']}><FreeAgentsPage /></RequireRole> },
              { path: 'teams', element: <TeamsPage /> },
              { path: 'teams/:id', element: <TeamDetailPage /> },
              { path: 'my-team', element: <RequireRole roles={['PLAYER', 'CAPTAIN']} exact><MyTeamPage /></RequireRole> },
              { path: 'tournaments', element: <TournamentsPage /> },
              { path: 'tournaments/:id', element: <TournamentDetailPage /> },
              { path: 'matches/:id', element: <MatchDetailPage /> },
              { path: 'matches/:id/lineup', element: <LineupPage /> },
              { path: 'referee/matches', element: <RequireRole roles={['REFEREE']} exact><RefereeMatchesPage /></RequireRole> },
              { path: 'admin/users', element: <RequireRole roles={['ADMIN', 'ORGANIZER']}><UsersAdminPage /></RequireRole> },
              { path: 'admin/audit', element: <RequireRole roles={['ADMIN']}><AuditPage /></RequireRole> },
              { path: '*', element: <NotFoundPage /> },
            ],
          },
        ],
      },
    ],
  },
])
