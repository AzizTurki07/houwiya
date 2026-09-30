import { fieldSpecsFor, flaggedFields } from './document-fields';

describe('document fields', () => {
  it('orders known passport fields and keeps unknown extracted keys', () => {
    const specs = fieldSpecsFor('PASSPORT', ['expiry_date', 'surname', 'document_number', 'place_of_issue']);
    expect(specs.map((s) => s.key)).toEqual(['surname', 'document_number', 'expiry_date', 'place_of_issue']);
    expect(specs[3].label).toBe('Place of issue');
  });

  it('validates CIN numbers as 8 digits', () => {
    const number = fieldSpecsFor('CIN', ['document_number'])[0];
    expect(number.pattern!.test('07845213')).toBeTrue();
    expect(number.pattern!.test('0784521')).toBeFalse();
    expect(number.pattern!.test('0784521A')).toBeFalse();
  });

  it('maps warnings onto the fields they concern', () => {
    const specs = fieldSpecsFor('PASSPORT', ['document_number', 'date_of_birth', 'expiry_date', 'surname']);
    const values = { document_number: 'P1', date_of_birth: null, expiry_date: '2020-01-01', surname: 'X' };

    const flags = flaggedFields(['MISSING_REQUIRED_FIELDS', 'DOCUMENT_EXPIRED'], values, specs);

    expect(flags.has('date_of_birth')).toBeTrue();
    expect(flags.get('expiry_date')).toContain('expired');
    expect(flags.has('document_number')).toBeFalse();
    expect(flags.has('surname')).toBeFalse();
  });

  it('flags the check-digit fields when the MRZ checksum fails', () => {
    const specs = fieldSpecsFor('PASSPORT', ['document_number', 'date_of_birth', 'expiry_date']);
    const flags = flaggedFields(['CHECKSUM_FAILED'], {}, specs);
    expect([...flags.keys()].sort()).toEqual(['date_of_birth', 'document_number', 'expiry_date']);
  });
});
