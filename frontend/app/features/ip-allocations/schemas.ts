import { z } from '../../lib/zod'
import { CIDR_PREFIX, IPV4_ADDRESS, ipv4ToNumber } from '../../lib/validation'

// Mirrors IpAllocationRequest: STATIC "192.168.1.10", RANGE "192.168.1.1-192.168.1.10" (start ≤ end),
// CIDR "192.168.1.0/24" (prefix 0-32, no leading zeros). The form has a second field for the range end.
export const ipAllocationFormSchema = z
  .object({
    type: z.enum(['STATIC', 'RANGE', 'CIDR'], { message: 'Choose a rule type.' }),
    value: z.string().trim(),
    rangeEnd: z.string().trim(),
  })
  .superRefine(({ type, value, rangeEnd }, context) => {
    const issue = (path: 'value' | 'rangeEnd', message: string) => context.addIssue({ code: 'custom', path: [path], message })
    if (type === 'STATIC') {
      if (!IPV4_ADDRESS.test(value)) issue('value', 'Enter an IPv4 address such as 192.168.1.5.')
      return
    }
    if (type === 'RANGE') {
      const startValid = IPV4_ADDRESS.test(value)
      const endValid = IPV4_ADDRESS.test(rangeEnd)
      if (!startValid) issue('value', 'Enter a valid start IPv4 address.')
      if (!endValid) issue('rangeEnd', 'Enter a valid end IPv4 address.')
      if (startValid && endValid && ipv4ToNumber(value) > ipv4ToNumber(rangeEnd)) {
        issue('rangeEnd', 'The end address must be greater than or equal to the start address.')
      }
      return
    }
    const [address, prefix, ...rest] = value.split('/')
    if (rest.length > 0 || prefix === undefined || !IPV4_ADDRESS.test(address)) {
      issue('value', 'Enter a subnet such as 192.168.1.0/24.')
    } else if (!CIDR_PREFIX.test(prefix)) {
      issue('value', 'The subnet prefix must be between /0 and /32.')
    } else if (ipv4ToNumber(address) % (2 ** (32 - Number(prefix))) !== 0) {
      issue('value', 'Use the network address with no host bits set, such as 10.0.0.0/8.')
    }
  })
export type IpAllocationFormValues = z.infer<typeof ipAllocationFormSchema>

/** The request body for POST /admin/ip-allocations. */
export function toIpAllocationInput(values: IpAllocationFormValues) {
  return {
    type: values.type,
    originalValue: values.type === 'RANGE' ? `${values.value}-${values.rangeEnd}` : values.value,
  }
}
