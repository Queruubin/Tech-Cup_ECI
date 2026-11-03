import { useState } from 'react'
import { Button } from '@/components/atoms/Button'
import { FileInput } from '@/components/atoms/FileInput'
import { Alert } from '@/components/molecules/Alert'
import { Modal } from '@/components/molecules/Modal'
import { IMAGE_ACCEPT, IMAGE_HINT, MAX_UPLOAD_BYTES } from '@/lib/uploads'
import { useMutation } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import type { PodiumEntry } from '@/types/api'
import { podiumPhotoUrl, tournamentsApi } from '../api'
import { podiumLabel } from '../history'

export interface PodiumPhotoViewerProps {
  tournamentId: number
  /** The podium position whose photo is shown; closed while null. */
  entry: PodiumEntry | null
  onClose: () => void
}

/** Public: the extra photo of a podium team, opened by clicking the team. */
export function PodiumPhotoViewer({ tournamentId, entry, onClose }: PodiumPhotoViewerProps) {
  return (
    <Modal
      open={entry !== null && entry.photoFileId !== null}
      onClose={onClose}
      size="lg"
      title={entry ? `${podiumLabel(entry.position)}: ${entry.teamName}` : 'Foto del podio'}
    >
      {entry?.photoFileId && (
        <img
          src={podiumPhotoUrl(tournamentId, entry.position, entry.photoFileId)}
          alt={`Foto de ${entry.teamName}, ${podiumLabel(entry.position).toLowerCase()}`}
          className="max-h-[70vh] w-full rounded-xl object-contain"
        />
      )}
    </Modal>
  )
}

export interface PodiumPhotoEditorProps {
  tournamentId: number
  /** The podium position being edited; closed while null. */
  entry: PodiumEntry | null
  onChanged: () => void
  onClose: () => void
}

/**
 * ADMIN / ORGANIZER: uploads, replaces or removes the optional photo of one podium position of a
 * finished tournament. The server deletes the replaced file.
 */
export function PodiumPhotoEditor({ tournamentId, entry, onChanged, onClose }: PodiumPhotoEditorProps) {
  const [file, setFile] = useState<File | null>(null)
  const upload = useMutation((input: { position: number; file: File }) =>
    tournamentsApi.uploadPodiumPhoto(tournamentId, input.position, input.file),
  )
  const remove = useMutation((position: number) => tournamentsApi.removePodiumPhoto(tournamentId, position))
  const busy = upload.loading || remove.loading
  const error = upload.error ?? remove.error

  const close = () => {
    setFile(null)
    upload.reset()
    remove.reset()
    onClose()
  }

  const save = () => {
    if (!entry || !file) return
    upload
      .mutate({ position: entry.position, file })
      .then(() => {
        toast.success(`Foto del ${podiumLabel(entry.position).toLowerCase()} guardada.`)
        onChanged()
        close()
      })
      .catch(() => undefined)
  }

  const removePhoto = () => {
    if (!entry) return
    remove
      .mutate(entry.position)
      .then(() => {
        toast.success(`Foto del ${podiumLabel(entry.position).toLowerCase()} eliminada.`)
        onChanged()
        close()
      })
      .catch(() => undefined)
  }

  return (
    <Modal
      open={entry !== null}
      onClose={busy ? () => undefined : close}
      title={entry ? `Foto del ${podiumLabel(entry.position).toLowerCase()}: ${entry.teamName}` : 'Foto del podio'}
      description="Opcional. La foto es pública y reemplaza a la anterior."
      footer={
        <>
          {entry?.photoFileId && (
            <Button variant="danger" onClick={removePhoto} loading={remove.loading} disabled={upload.loading}>
              Quitar foto
            </Button>
          )}
          <Button variant="outline" onClick={close} disabled={busy}>
            Cancelar
          </Button>
          <Button onClick={save} disabled={!file || remove.loading} loading={upload.loading}>
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
