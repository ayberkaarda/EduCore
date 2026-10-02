export function LogoMark() {
  return (
    <svg viewBox="0 0 48 48" className="logo-mark" aria-hidden="true">
      <g fill="currentColor">
        <rect x="13" y="13.5" width="22" height="5" rx="1.5" />
        <rect x="13" y="21.5" width="13" height="5" rx="1.5" />
        <rect x="30" y="21.5" width="5" height="5" rx="1.5" />
        <rect x="13" y="29.5" width="22" height="5" rx="1.5" />
      </g>
    </svg>
  )
}

export function FullLogo() {
  return (
    <span className="full-logo">
      <img className="logo-light" src="/brand/logo-full.svg" alt="EduCore" />
      <img className="logo-dark" src="/brand/logo-full-dark.svg" alt="EduCore" />
    </span>
  )
}
