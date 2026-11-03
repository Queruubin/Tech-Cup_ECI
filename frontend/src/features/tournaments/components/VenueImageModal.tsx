import { useState } from 'react'
import { Button } from '@/components/atoms/Button'
import { FileInput } from '@/components/atoms/FileInput'
import { Alert } from '@/components/molecules/Alert'
import { Modal } from '@/components/molecules/Modal'
import { IMAGE_ACCEPT, IMAGE_HINT, MAX_UPLOAD_BYTES } from '@/lib/uploads'
import { useMutation } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import type { VenueResponse } from '@/types/api'
import { tournamentsApi } from '../api'

export interface VenueImageModalProps {
  tournamentId: number
  /** The venue being edited; the modal is closed while null. */
  venue: VenueResponse | null
  onChanged: (venue: VenueResponse) => void
  onClose: () => void
}

/**
 * Replaces or removes a venue's photo (DRAFT, ACTIVE or IN_PROGRESS tournaments; the server
 * refuses it while suspended or finished). The previous image is deleted by the server.
 */
export function VenueImageModal({ tournamentId, venue, onChanged, onClose }: VenueImageModalProps) {
  const [file, setFile] = useState<File | null>(null)
  const replace = useMutation(async (input: { venueId: number; file: File }) => {
    const updated = await tournamentsApi.replaceVenueImage(tournamentId, input.venueId, input.file)
    onChanged(updated)
    return updated
  })
  const remove = useMutation(async (venueId: number) => {
    const updated = await tournamentsApi.removeVenueImage(tournamentId, venueId)
    onChanged(updated)
    return updated
  })
  const busy = replace.loading || remove.loading
  const error = replace.error ?? remove.error

  const save = () => {
    if (!venue || !file) return
    replace
      .mutate({ venueId: venue.id, file })
      .then(() => {
        toast.success('Foto de la cancha actualizada.')
        onClose()
      })
      .catch(() => undefined)
  }

  const removeImage = () => {
    if (!venue) return
    remove
      .mutate(venue.id)
      .then(() => {
        toast.success('Foto de la cancha eliminada.')
        onClose()
      })
      .catch(() => undefined)
  }

  return (
    <Modal
      open={venue !== null}
      onClose={busy ? () => undefined : onClose}
      title={venue ? `Foto de ${venue.name}` : 'Foto de la cancha'}
      description="La nueva imagen reemplaza la actual para todos."
      footer={
        <>
          {venue?.imageFileId && (
            <Button variant="danger" onClick={removeImage} loading={remove.loading} disabled={replace.loading}>
              Quitar foto
            </Button>
          )}
          <Button variant="outline" onClick={onClose} disabled={busy}>
            Cancelar
          </Button>
          <Button onClick={save} disabled={!file || remove.loading} loading={replace.loading}>
            Guardar foto
          </Button>
        </>
      }
    >
      {error && (
        <Alert kind="error" className="mb-3">
          {error}
        </Alert>
      )}
      <FileInput accept={IMAGE_ACCEPT} maxBytes={MAX_UPLOAD_BYTES} value={file} onChange={setFile} hint={IMAGE_HINT} />
    </Modal>
  )
}
