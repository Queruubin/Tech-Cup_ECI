import { Button } from '@/components/atoms/Button'
import { Card } from '@/components/molecules/Card'
import { errorMessage } from '@/lib/api'
import { saveBlob } from '@/lib/files'
import { useMutation } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import { tournamentsApi } from '../api'

export interface PlayersExportCardProps {
  tournamentId: number
}

/**
 * ADMIN only: downloads the players of the tournament as an Excel file (approved teams, plus a
 * sheet with the teams under review). The file name comes from the server.
 */
export function PlayersExportCard({ tournamentId }: PlayersExportCardProps) {
  const download = useMutation(async () => {
    const file = await tournamentsApi.exportPlayers(tournamentId)
    saveBlob(file.blob, file.filename ?? `jugadores-torneo-${tournamentId}.xlsx`)
  })

  const handleDownload = () => {
    download
      .mutate()
      .then(() => toast.success('Descarga iniciada.'))
      .catch((cause: unknown) => toast.error(errorMessage(cause, 'No fue posible descargar el archivo.')))
  }

  return (
    <Card
      title="Jugadores inscritos"
      description="Excel con los datos de los jugadores de los equipos aprobados y, en otra hoja, de los que están en revisión. Contiene datos personales: guárdelo con cuidado."
    >
      <Button size="sm" variant="outline" loading={download.loading} onClick={handleDownload}>
        Descargar Excel de jugadores
      </Button>
    </Card>
  )
}
