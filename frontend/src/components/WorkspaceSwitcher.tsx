import { companyName, roleLabel, roleNote, type Membership } from "../lib/memberships";

export function WorkspaceSwitcher({
  memberships,
  tenant,
  onChange,
}: {
  memberships: Membership[];
  tenant: string;
  onChange: (slug: string) => void;
}) {
  const current = memberships.find((membership) => membership.slug === tenant);
  return (
    <label className="block">
      <span className="font-mono text-[11px] tracking-[0.16em] text-[#c4a574] uppercase">Company</span>
      <select
        className="mt-2 w-full rounded-md border border-[#3d342c] bg-[#2c261f] px-2 py-2 text-sm text-[var(--sidebar-ink)]"
        value={tenant}
        onChange={(event) => onChange(event.target.value)}
        aria-label="Company"
      >
        {memberships.map((membership) => (
          <option key={membership.slug} value={membership.slug}>
            {companyName(membership.slug)}
          </option>
        ))}
      </select>
      <p className="mt-2 text-xs leading-5 text-[#c4a574]">{current ? roleNote(current.role) : ""}</p>
      <p className="mt-1 font-mono text-[11px] text-[#8a7d6e]">{current ? roleLabel(current.role) : ""}</p>
    </label>
  );
}
