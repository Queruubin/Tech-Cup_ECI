import { useEffect, useMemo, useState } from 'react'
import { Button } from '@/components/atoms/Button'
import { FileInput } from '@/components/atoms/FileInput'
import { Alert } from '@/components/molecules/Alert'
import { ConfirmDialog, Modal } from '@/components/molecules/Modal'
import { errorMessage } from '@/lib/api'
import { IMAGE_ACCEPT, IMAGE_HINT, MAX_UPLOAD_BYTES } from '@/lib/uploads'
import { useMutation } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import type { TeamResponse } from '@/types/api'
import { teamsApi } from '../api'
import { REMOVE_TEAM_PHOTO_QUESTION } from '../teamPhoto'
import { TeamPhoto } from './TeamPhoto'

export interface TeamPhotoModalProps {
  /** The team being edited; the modal is closed while null. */
  team: TeamResponse | null
  onChanged: (team: TeamResponse) => void
  onClose: () => void
}

/** Object URL of the picked file, revoked when it changes or the modal unmounts. */
function useLocalPreview(file: File | null): string | null {
  const url = useMemo(
    () => (file && typeof URL.createObjectURL === 'function' ? URL.createObjectURL(file) : null),
    [file],
  )
  useEffect(
    () => () => {
      if (url) URL.revokeObjectURL(url)
    },
    [url],
  )
  return url
}

/**
 * Sets, replaces or removes a team photo (captain of the team, ORGANIZER or ADMIN). Allowed at any
 * time, also while the team plays a tournament; the server deletes the previous image.
 */
export function TeamPhotoModal({ team, onChanged, onClose }: TeamPhotoModalProps) {
  const [file, setFile] = useState<File | null>(null)
  const [confirmRemove, setConfirmRemove] = useState(false)
  const preview = useLocalPreview(file)

  const replace = useMutation(async (input: { teamId: number; file: File }) => {
    const updated = await teamsApi.replacePhoto(input.teamId, input.file)
    onChanged(updated)
    return updated
  })
  const remove = useMutation(async (teamId: number) => {
    const updated = await teamsApi.removePhoto(teamId)
    onChanged(updated)
    return updated
  })
  const busy = replace.loading || remove.loading

  const close = () => {
    setFile(null)
    replace.reset()
    onClose()
  }

  const save = () => {
    if (!team || !file) return
    replace
      .mutate({ teamId: team.id, file })
      .then(() => {
        toast.success('Foto del equipo actualizada.')
        close()
      })
      .catch(() => undefined)
  }

  const removePhoto = () => {
    if (!team) return
    remove
      .mutate(team.id)
      .then(() => {
        toast.success('Foto del equipo eliminada.')
        setConfirmRemove(false)
        close()
      })
      .catch((cause: unknown) => {
        setConfirmRemove(false)
        toast.error(errorMessage(cause, 'No fue posible quitar la foto.'))
      })
  }

  return (
    <>
      <Modal
        open={team !== null && !confirmRemove}
        onClose={busy ? () => undefined : close}
        title={team?.photoFileId ? 'Cambiar foto del equipo' : 'Agregar foto del equipo'}
        description={team ? `${team.name}. La nueva imagen reemplaza la actual para todos.` : undefined}
        footer={
          <>
            {team?.photoFileId && (
              <Button variant="danger" onClick={() => setConfirmRemove(true)} disabled={busy}>
                Quitar foto
              </Button>
            )}
            <Button variant="outline" onClick={close} disabled={busy}>
              Cancelar
            </Button>
            <Button onClick={save} disabled={!file || remove.loading} loading={replace.loading}>
              Guardar foto
            </Button>
          </>
        }
      >
        {replace.error && (
          <Alert kind="error" className="mb-3">
            {replace.error}
          </Alert>
        )}
        <div className="mb-4 flex items-center gap-4">
          {preview ? (
            <img
              src={preview}
              alt="Vista previa de la nueva foto"
              className="h-20 w-20 shrink-0 rounded-2xl border border-stone-200 object-cover"
            />
          ) : (
            team && <TeamPhoto name={team.name} photoFileId={team.photoFileId} size="lg" />
          )}
          <p className="text-sm text-stone-600">
            {preview ? 'Vista previa de la nueva foto.' : team?.photoFileId ? 'Foto actual del equipo.' : 'El equipo aún no tiene foto.'}
          </p>
        </div>
        <FileInput accept={IMAGE_ACCEPT} maxBytes={MAX_UPLOAD_BYTES} value={file} onChange={setFile} hint={IMAGE_HINT} />
      </Modal>

      <ConfirmDialog
        open={team !== null && confirmRemove}
        title={team ? `Quitar la foto de ${team.name}` : 'Quitar foto'}
        description={REMOVE_TEAM_PHOTO_QUESTION}
        confirmLabel="Quitar foto"
        danger
        loading={remove.loading}
        onConfirm={removePhoto}
        onCancel={() => setConfirmRemove(false)}
      />
    </>
  )
}
