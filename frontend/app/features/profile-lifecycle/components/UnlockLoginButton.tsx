import { useMutation } from '@tanstack/react-query'
import { useAuth } from '../../auth/auth-context'
import { unlockLogin } from '../api'
import { toast } from '../../../lib/toast'
import { toastApiError } from '../../../lib/error-messages'

export default function UnlockLoginButton({ accountId }: { accountId: number }) {
  const { user } = useAuth()
  const unlock = useMutation({ mutationFn: unlockLogin, onSuccess: () => toast.success('Sign-in unlocked.'), onError: error => toastApiError(error, 'Could not unlock sign-in.') })
  if (user?.role !== 'ADMIN') return null
  return <button type="button" className="btn-secondary" disabled={unlock.isPending} onClick={() => unlock.mutate(accountId)}>Unlock sign-in</button>
}
