import { useState } from 'react'
import { Button } from '@/components/atoms/Button'
import { downloadFile } from '@/lib/files'
import { toast } from '@/store/ui.store'

export interface RulebookLinkProps {
  fileId: string | null
  tournamentName: string
}

export function RulebookLink({ fileId, tournamentName }: RulebookLinkProps) {
  const [downloading, setDownloading] = useState(false)
  if (!fileId) return <p className="text-sm text-stone-500">El reglamento aún no ha sido publicado.</p>

  const handleDownload = () => {
    setDownloading(true)
    downloadFile(fileId, `Reglamento - ${tournamentName}.pdf`)
      .catch((cause: unknown) => toast.error(cause instanceof Error ? cause.message : 'No fue posible descargar el reglamento.'))
      .finally(() => setDownloading(false))
  }

  return (
    <Button variant="outline" size="sm" onClick={handleDownload} loading={downloading}>
      Descargar reglamento (PDF)
    </Button>
  )
}
