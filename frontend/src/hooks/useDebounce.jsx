import { useState, useEffect } from 'react';

// Kullanıcı arama kutusuna hızlıca yazı yazarken, API'ye saniyede 10 istek atmak yerine
// kullanıcının yazmayı bitirmesini (belirlenen süre kadar) bekler.
export function useDebounce(value, delay) {
    const [debouncedValue, setDebouncedValue] = useState(value);

    useEffect(() => {
        const handler = setTimeout(() => {
            setDebouncedValue(value);
        }, delay);

        return () => {
            clearTimeout(handler);
        };
    }, [value, delay]);

    return debouncedValue;
}