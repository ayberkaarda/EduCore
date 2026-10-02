import { z } from '../../lib/zod'
export const MAX_IMPORT_BYTES = 5 * 1024 * 1024
export const importFileSchema = z.custom<File>(value => typeof File !== 'undefined' && value instanceof File, 'Choose a CSV file.').refine(file => !!file && /\.csv$/i.test(file.name), 'Choose a .csv file.').refine(file => !!file && file.size > 0, 'Choose a non-empty CSV file.').refine(file => !!file && file.size <= MAX_IMPORT_BYTES, 'The CSV file must be 5 MB or smaller.')
