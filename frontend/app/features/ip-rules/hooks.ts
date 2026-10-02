import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { deleteIpRule, ipRuleKeys, listIpRules, saveIpRule, type DenyRuleInput } from './api'
export function useIpRules(page: number) { return useQuery({ queryKey: [...ipRuleKeys.all, page], queryFn: ({ signal }) => listIpRules(page, signal) }) }
export function useSaveIpRule() {
  const client = useQueryClient()
  return useMutation({ mutationFn: ({ body, id }: { body: DenyRuleInput; id?: number }) => saveIpRule(body, id), onSuccess: () => client.invalidateQueries({ queryKey: ipRuleKeys.all }) })
}
export function useDeleteIpRule() {
  const client = useQueryClient()
  return useMutation({ mutationFn: deleteIpRule, onSuccess: () => client.invalidateQueries({ queryKey: ipRuleKeys.all }) })
}
